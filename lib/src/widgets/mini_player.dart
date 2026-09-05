import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';

import '../models/song_data.dart';
import '../theme/app_theme.dart';
import '../utils/audio_formats.dart';

class MiniPlayer extends StatefulWidget {
  final SongData song;
  final SongData? nextSong;
  final SongData? previousSong;
  final AudioPlayer player;
  final VoidCallback onTap;
  final VoidCallback onPrevious;
  final VoidCallback onNext;

  const MiniPlayer({
    super.key,
    required this.song,
    this.nextSong,
    this.previousSong,
    required this.player,
    required this.onTap,
    required this.onPrevious,
    required this.onNext,
  });

  @override
  State<MiniPlayer> createState() => _MiniPlayerState();
}

class _MiniPlayerState extends State<MiniPlayer>
    with SingleTickerProviderStateMixin {
  static const double _hMax = 96.0;
  static const double _vMax = 70.0;
  static const double _hThreshold = 40.0;
  static const double _vThreshold = 34.0;
  static const double _velocityThreshold = 650.0;

  Offset _dragOffset = Offset.zero;
  double _dragScale = 1.0;
  Axis? _lockedAxis;
  bool _isScrubbing = false;
  double? _scrubRatio;
  int _slideDir = 1;
  // Transición de cambio por swipe: slide-out hacia el borde -> cambio de
  // tema -> entrada desde el lado opuesto con rebote (antes el contenido
  // saltaba a Offset.zero en seco y se veía un corte).
  bool _exiting = false;
  bool? _switchAfterExit; // true = siguiente, false = anterior
  // Fase de entrada y supresión del AnimatedSwitcher: con el switcher
  // activo el texto viejo y el nuevo se APILABAN durante la transición
  // (doble animación = solape). Durante el gesto, la única animación es
  // la del contenido completo.
  bool _entering = false;
  bool _suppressSwitcherAnim = false;
  // Supresor del onTap padre tras tocar la barra de progreso.
  DateTime? _lastProgressTap;

  late final AnimationController _spring;
  Animation<Offset>? _springAnim;

  // Posición a 500ms en vez del stream por defecto (~200ms): para una
  // barra de 3px la diferencia es invisible y se eliminan ~15 rebuilds
  // con sombras blur por canción.
  late final Stream<Duration> _positionStream =
      widget.player.createPositionStream(
    steps: 8,
    minPeriod: const Duration(milliseconds: 500),
    maxPeriod: const Duration(milliseconds: 500),
  );

  @override
  void initState() {
    super.initState();
    _spring = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 320),
    );
    _spring.addListener(_onSpringTick);
    _spring.addStatusListener(_onSpringStatus);
  }

  @override
  void dispose() {
    _spring.dispose();
    super.dispose();
  }

  void _onSpringTick() {
    if (!mounted || _springAnim == null) return;
    setState(() => _dragOffset = _springAnim!.value);
  }

  void _onSpringStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed) return;
    final switchNext = _switchAfterExit;
    if (switchNext != null) {
      // Terminó el slide-out: cambia de tema y entra desde el contrario
      // con el switcher SUPRIMIDO (el movimiento lo hace el contenido).
      _switchAfterExit = null;
      _slideDir = switchNext ? 1 : -1;
      final enterX = switchNext ? _hMax * 0.5 : -_hMax * 0.5;
      if (mounted) {
        setState(() {
          _dragOffset = Offset(enterX, 0);
          _dragScale = 1.0;
          _lockedAxis = null;
          _exiting = false;
          _entering = true;
          _suppressSwitcherAnim = true;
        });
      }
      if (switchNext) {
        widget.onNext();
      } else {
        widget.onPrevious();
      }
      _animateBack();
      return;
    }
    if (_entering) _finishEntry();
  }

  void _finishEntry() {
    _entering = false;
    _suppressSwitcherAnim = false;
    if (mounted) setState(() {});
  }

  // Retorno animado (con ligero rebote) al soltar sin cambiar de canción
  void _animateBack() {
    final from = _dragOffset;
    _spring.duration = const Duration(milliseconds: 320);
    if (from.distance < 0.5) {
      _springAnim = null;
      _exiting = false;
      if (_entering) _finishEntry();
      if (mounted) setState(() => _dragOffset = Offset.zero);
      return;
    }
    _springAnim = Tween<Offset>(
      begin: from,
      end: Offset.zero,
    ).animate(CurvedAnimation(parent: _spring, curve: Curves.easeOutBack));
    _spring
      ..reset()
      ..forward();
  }

  /// Slide-out del contenido hacia el borde en la dirección del gesto;
  /// el cambio de tema ocurre al terminar (ver [_onSpringStatus]).
  void _commitSlide(bool next) {
    _slideDir = next ? 1 : -1;
    _switchAfterExit = next;
    _exiting = true;
    _lockedAxis = null;
    final exitX = next ? -_hMax : _hMax;
    _springAnim = Tween<Offset>(
      begin: _dragOffset,
      end: Offset(exitX, 0),
    ).animate(CurvedAnimation(parent: _spring, curve: Curves.easeInCubic));
    _spring
      ..duration = const Duration(milliseconds: 150)
      ..reset()
      ..forward();
  }

  void _cancelSpring() {
    if (_spring.isAnimating) {
      _spring.stop();
      _springAnim = null;
      _switchAfterExit = null;
      _exiting = false;
      if (_entering) _finishEntry();
    }
  }

  void _setScrubbing(bool v) {
    _isScrubbing = v;
    if (v) {
      _cancelSpring();
      _lockedAxis = null;
      _dragOffset = Offset.zero;
      _dragScale = 1.0;
    } else {
      _scrubRatio = null;
    }
  }

  void _onPanStart(DragStartDetails details) {
    if (_isScrubbing) return;
    _cancelSpring();
    _lockedAxis = null;
  }

  void _onPanUpdate(DragUpdateDetails details) {
    if (_isScrubbing) return;
    setState(() {
      if (_lockedAxis == null) {
        final tentativeDx = _dragOffset.dx + details.delta.dx;
        final tentativeDy = _dragOffset.dy + details.delta.dy;
        if (tentativeDx.abs() > 6 || tentativeDy.abs() > 6) {
          _lockedAxis = tentativeDx.abs() > tentativeDy.abs()
              ? Axis.horizontal
              : Axis.vertical;
        }
      }

      double dx = _dragOffset.dx;
      double dy = _dragOffset.dy;

      if (_lockedAxis == Axis.horizontal) {
        // Resistencia progresiva: cuanto más lejos, más cuesta arrastrar
        final raw = dx + details.delta.dx;
        final sign = raw.isNegative ? -1.0 : 1.0;
        final abs = raw.abs();
        dx = sign * _hMax * (1 - 1 / (abs / (_hMax * 0.45) + 1));
        dy = 0;
        _dragScale = 1.0;
      } else if (_lockedAxis == Axis.vertical) {
        dx = 0;
        dy = (dy + details.delta.dy).clamp(-_vMax, 20.0);
        _dragScale = dy < 0 ? (1.0 + (-dy / 420)).clamp(1.0, 1.08) : 1.0;
      } else {
        dx = (dx + details.delta.dx).clamp(-_hMax, _hMax);
        dy = (dy + details.delta.dy).clamp(-_vMax, 20.0);
        _dragScale = dy < 0 ? (1.0 + (-dy / 420)).clamp(1.0, 1.08) : 1.0;
      }

      _dragOffset = Offset(dx, dy);
    });
  }

  void _onPanCancel() {
    if (_isScrubbing) {
      _isScrubbing = false;
      return;
    }
    _animateBack();
    _dragScale = 1.0;
    _lockedAxis = null;
  }

  void _onPanEnd(DragEndDetails details) {
    if (_isScrubbing) {
      _isScrubbing = false;
      return;
    }
    final v = details.velocity.pixelsPerSecond;
    final dx = _dragOffset.dx;
    final dy = _dragOffset.dy;
    final isHorizontal =
        _lockedAxis == Axis.horizontal ||
        (_lockedAxis == null && dx.abs() > dy.abs());

    if (isHorizontal) {
      final next = dx < -_hThreshold || v.dx < -_velocityThreshold;
      final prev = dx > _hThreshold || v.dx > _velocityThreshold;
      if (next || prev) {
        // Slide-out animado hacia el borde; el cambio de canción y la
        // entrada desde el lado contrario ocurren al completar.
        _dragScale = 1.0;
        _commitSlide(next);
      } else {
        _animateBack();
        _dragScale = 1.0;
        _lockedAxis = null;
      }
    } else {
      _lockedAxis = null;
      if (dy < -_vThreshold || v.dy < -_velocityThreshold) {
        setState(() {
          _dragOffset = Offset.zero;
          _dragScale = 1.0;
        });
        widget.onTap();
      } else {
        _animateBack();
        _dragScale = 1.0;
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDraggingH =
        _lockedAxis == Axis.horizontal && _dragOffset.dx.abs() > 8 ||
        (_lockedAxis == null &&
            _dragOffset.dx.abs() > 8 &&
            _dragOffset.dx.abs() > _dragOffset.dy.abs());
    final dragProgress = (_dragOffset.dx.abs() / _hMax).clamp(0.0, 1.0);
    final isDraggingLeft = _dragOffset.dx < 0;
    final peekSong = isDraggingLeft ? widget.nextSong : widget.previousSong;
    // El peek solo durante el gesto activo (no en la animación de
    // salida/entrada del cambio de canción).
    final showPeek = isDraggingH && peekSong != null && !_spring.isAnimating;

    // El contenido sigue al dedo 1:1 (la curva de resistencia ya suaviza)
    // y funde a un mínimo para no dejar la tarjeta fantasma a mitad de gesto.
    final horizontalShift = isDraggingH ? _dragOffset.dx : 0.0;
    final verticalShift = _lockedAxis == Axis.vertical
        ? _dragOffset.dy * 0.18
        : 0.0;
    final contentScale = isDraggingH
        ? (1.0 - 0.05 * dragProgress)
        : (_lockedAxis == Axis.vertical ? _dragScale : 1.0);
    final double contentOpacity;
    if (_exiting) {
      // Salida: funde a 0 con el progreso real de la animación.
      contentOpacity = (1.0 - _spring.value).clamp(0.0, 1.0);
    } else if (_entering) {
      // Entrada: aparece desde 0 mientras retorna al centro.
      contentOpacity = _spring.value.clamp(0.0, 1.0);
    } else if (isDraggingH) {
      contentOpacity = (1 - dragProgress * 0.85).clamp(0.15, 1.0);
    } else {
      contentOpacity = 1.0;
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 8),
      child: GestureDetector(
        onTap: () {
          // Si el toque vino de la barra de progreso (<300ms), ya hizo
          // seek: no abrir el full player encima.
          if (_lastProgressTap != null &&
              DateTime.now().difference(_lastProgressTap!).inMilliseconds <
                  300) {
            return;
          }
          widget.onTap();
        },
        onPanStart: _onPanStart,
        onPanUpdate: _onPanUpdate,
        onPanEnd: _onPanEnd,
        onPanCancel: _onPanCancel,
        behavior: HitTestBehavior.opaque,
        child: Transform.scale(
          scale: _dragScale,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(AppTheme.radiusM),
            child: BackdropFilter(
              filter: ImageFilter.blur(
                sigmaX: AppTheme.blurSigma,
                sigmaY: AppTheme.blurSigma,
              ),
              child: Container(
                decoration: BoxDecoration(
                  color: AppTheme.glassSurface.withValues(alpha: 0.72),
                  borderRadius: BorderRadius.circular(AppTheme.radiusM),
                  border: Border.all(
                    color: AppTheme.glassBorderStrong,
                    width: 0.9,
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.32),
                      blurRadius: 24,
                      offset: const Offset(0, 10),
                    ),
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.14),
                      blurRadius: 8,
                      offset: const Offset(0, 2),
                    ),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(AppTheme.radiusM - 1),
                  child: SizedBox(
                    height: 60,
                    child: Stack(
                      children: [
                        // Peek del siguiente/anterior: misma geometría que la
                        // fila principal (misma arte, mismos paddings) y solo
                        // funde la opacidad — sin parallax ni huecos raros.
                        if (showPeek)
                          Positioned.fill(
                            child: IgnorePointer(
                              child: Opacity(
                                opacity: dragProgress.clamp(0.0, 1.0),
                                child: Padding(
                                  padding: const EdgeInsets.fromLTRB(
                                    10,
                                    0,
                                    8,
                                    3,
                                  ),
                                  child: Row(
                                    children: [
                                      ClipRRect(
                                        borderRadius: BorderRadius.circular(
                                          9,
                                        ),
                                        child: peekSong.albumArt != null
                                            ? Image.memory(
                                                peekSong.albumArt!,
                                                width: 40,
                                                height: 40,
                                                fit: BoxFit.cover,
                                                cacheWidth: 120,
                                                gaplessPlayback: true,
                                              )
                                            : Container(
                                                width: 40,
                                                height: 40,
                                                decoration: BoxDecoration(
                                                  color: AppTheme
                                                      .glassSurfaceLight
                                                      .withValues(alpha: 0.9),
                                                  borderRadius:
                                                      BorderRadius.circular(
                                                        9,
                                                      ),
                                                  border: Border.all(
                                                    color:
                                                        AppTheme.glassBorder,
                                                    width: 0.7,
                                                  ),
                                                ),
                                                child: const Icon(
                                                  Icons.music_note_rounded,
                                                  color:
                                                      AppTheme.textSecondary,
                                                  size: 18,
                                                ),
                                              ),
                                      ),
                                      const SizedBox(width: 10),
                                      Expanded(
                                        child: Column(
                                          mainAxisSize: MainAxisSize.min,
                                          crossAxisAlignment:
                                              CrossAxisAlignment.start,
                                          children: [
                                            Text(
                                              isDraggingLeft
                                                  ? 'A continuación'
                                                  : 'Anterior',
                                              style: TextStyle(
                                                fontSize: 10,
                                                fontWeight: FontWeight.w600,
                                                letterSpacing: 0.4,
                                                color: Theme.of(
                                                  context,
                                                ).colorScheme.primary,
                                              ),
                                              maxLines: 1,
                                            ),
                                            const SizedBox(height: 2),
                                            Text(
                                              peekSong.displayTitle,
                                              style: const TextStyle(
                                                fontWeight: FontWeight.w600,
                                                fontSize: 13,
                                                letterSpacing: -0.2,
                                                color: AppTheme.textPrimary,
                                              ),
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                            ),
                                          ],
                                        ),
                                      ),
                                      Icon(
                                        isDraggingLeft
                                            ? Icons.skip_next_rounded
                                            : Icons.skip_previous_rounded,
                                        color: Theme.of(
                                          context,
                                        ).colorScheme.primary,
                                        size: 20,
                                      ),
                                      const SizedBox(width: 6),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        // Contenido actual
                        Positioned.fill(
                          child: IgnorePointer(
                            ignoring: isDraggingH,
                            child: Transform.translate(
                              offset: Offset(horizontalShift, verticalShift),
                              child: Transform.scale(
                                scale: contentScale,
                                child: Opacity(
                                  opacity: contentOpacity,
                                  child: Stack(
                                    children: [
                                      // Contenido centrado en toda la tarjeta
                                      // (offset abajo para igualar el margen
                                      // superior con el hueco hasta la barra)
                                      Positioned.fill(
                                        child: Padding(
                                          padding: const EdgeInsets.only(
                                            bottom: 3,
                                          ),
                                          child: _buildContentRow(context),
                                        ),
                                      ),
                                      // Barra superpuesta en el borde inferior
                                      Positioned(
                                        left: 0,
                                        right: 0,
                                        bottom: 0,
                                        child: _buildProgressBar(context),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _titleColumn(SongData song) {
    return Column(
      key: ValueKey(song.playUri),
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          song.displayTitle,
          style: const TextStyle(
            fontWeight: FontWeight.w600,
            fontSize: 13.5,
            letterSpacing: -0.2,
            color: AppTheme.textPrimary,
          ),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        const SizedBox(height: 2),
        Text(
          song.artist ?? song.album ?? AppStrings.unknown,
          style: const TextStyle(
            color: AppTheme.textSecondary,
            fontSize: 11.5,
            fontWeight: FontWeight.w400,
          ),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ],
    );
  }

  Widget _buildContentRow(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 0, 8, 0),
      child: Row(
        children: [
          Hero(
            tag: 'playerArt_${widget.song.playUri}',
            child: ClipRRect(
              borderRadius: BorderRadius.circular(9),
              child: widget.song.albumArt != null
                  ? Image.memory(
                      widget.song.albumArt!,
                      width: 40,
                      height: 40,
                      fit: BoxFit.cover,
                      cacheWidth: 120,
                      gaplessPlayback: true,
                    )
                  : Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: AppTheme.glassSurfaceLight.withValues(
                          alpha: 0.9,
                        ),
                        borderRadius: BorderRadius.circular(9),
                        border: Border.all(
                          color: AppTheme.glassBorder,
                          width: 0.7,
                        ),
                      ),
                      child: const Icon(
                        Icons.music_note_rounded,
                        color: AppTheme.textSecondary,
                        size: 18,
                      ),
                    ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            // Durante el cambio por swipe el switcher se suprime: el texto
            // viejo y el nuevo no se apilan (ese era el solape); el único
            // movimiento es el del contenido completo (slide + fade).
            child: _suppressSwitcherAnim
                ? _titleColumn(widget.song)
                : AnimatedSwitcher(
                    duration: const Duration(milliseconds: 260),
                    switchInCurve: Curves.easeOutCubic,
                    switchOutCurve: Curves.easeInCubic,
                    transitionBuilder: (child, anim) {
                      final isIncoming =
                          child.key == ValueKey(widget.song.playUri);
                      final dir = _slideDir.toDouble();
                      return FadeTransition(
                        opacity: anim,
                        child: SlideTransition(
                          position: Tween<Offset>(
                            begin:
                                Offset(isIncoming ? dir * 0.2 : -dir * 0.2, 0),
                            end: Offset.zero,
                          ).animate(anim),
                          child: child,
                        ),
                      );
                    },
                    child: _titleColumn(widget.song),
                  ),
          ),
          _miniIconButton(
            icon: Icons.skip_previous_rounded,
            onPressed: widget.onPrevious,
          ),
          StreamBuilder<PlayerState>(
            stream: widget.player.playerStateStream,
            builder: (context, snapshot) {
              final playing = snapshot.data?.playing ?? false;
              final aura = Theme.of(context).colorScheme.primary;
              return AnimatedContainer(
                duration: const Duration(milliseconds: 420),
                curve: Curves.easeInOutCubic,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(10),
                  color: aura,
                  boxShadow: [
                    BoxShadow(
                      color: aura.withValues(alpha: 0.32),
                      blurRadius: 12,
                      offset: const Offset(0, 3),
                    ),
                  ],
                ),
                child: IconButton(
                  icon: Icon(
                    playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
                    color: Colors.white,
                    size: 20,
                  ),
                  onPressed: () {
                    if (playing) {
                      widget.player.pause();
                    } else {
                      widget.player.play();
                    }
                  },
                  padding: const EdgeInsets.all(6),
                  constraints: const BoxConstraints(),
                  splashColor: Colors.transparent,
                  highlightColor: Colors.transparent,
                  style: IconButton.styleFrom(
                    splashFactory: NoSplash.splashFactory,
                  ),
                ),
              );
            },
          ),
          _miniIconButton(
            icon: Icons.skip_next_rounded,
            onPressed: widget.onNext,
          ),
          const SizedBox(width: 2),
        ],
      ),
    );
  }

  // Barra de progreso: línea fina al borde inferior, arrastrable y con glow
  Widget _buildProgressBar(BuildContext context) {
    final aura = Theme.of(context).colorScheme.primary;
    return StreamBuilder<Duration>(
      stream: _positionStream,
      builder: (context, snapshot) {
        final pos = snapshot.data ?? Duration.zero;
        final dur = widget.player.duration ?? Duration.zero;
        final rawValue = dur.inMilliseconds > 0
            ? (pos.inMilliseconds / dur.inMilliseconds).clamp(0.0, 1.0)
            : 0.0;
        final value = (_isScrubbing && _scrubRatio != null)
            ? _scrubRatio!
            : rawValue;
        return LayoutBuilder(
          builder: (context, constraints) {
            final trackW = constraints.maxWidth;
            void updateScrub(double dx) {
              if (trackW <= 0) return;
              setState(() => _scrubRatio = (dx / trackW).clamp(0.0, 1.0));
            }

            void commitScrub() {
              if (_scrubRatio != null && dur.inMilliseconds > 0) {
                widget.player.seek(
                  Duration(
                    milliseconds: (dur.inMilliseconds * _scrubRatio!).round(),
                  ),
                );
              }
              _setScrubbing(false);
              if (mounted) setState(() {});
            }

            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              // onTapDown para seek preciso por posición; marca el instante
              // para que el onTap del padre (abrir player) lo ignore y no
              // haga seek + abrir a la vez.
              onTapDown: (d) {
                if (trackW <= 0 || dur.inMilliseconds <= 0) return;
                _lastProgressTap = DateTime.now();
                final ratio = (d.localPosition.dx / trackW).clamp(0.0, 1.0);
                widget.player.seek(
                  Duration(milliseconds: (dur.inMilliseconds * ratio).round()),
                );
              },
              onHorizontalDragStart: (d) {
                _setScrubbing(true);
                updateScrub(d.localPosition.dx);
              },
              onHorizontalDragUpdate: (d) => updateScrub(d.localPosition.dx),
              onHorizontalDragEnd: (_) => commitScrub(),
              onHorizontalDragCancel: () {
                _setScrubbing(false);
                if (mounted) setState(() {});
              },
              child: SizedBox(
                height: 14,
                width: double.infinity,
                child: Align(
                  alignment: Alignment.bottomCenter,
                  child: SizedBox(
                    height: 3,
                    width: double.infinity,
                    child: Stack(
                      clipBehavior: Clip.none,
                      children: [
                        Container(
                          height: 3,
                          width: double.infinity,
                          color: Colors.white.withValues(alpha: 0.10),
                        ),
                        Positioned(
                          left: 0,
                          top: 0,
                          bottom: 0,
                          width: (trackW * value).clamp(0.0, trackW),
                          child: Container(
                            decoration: BoxDecoration(
                              color: aura,
                              borderRadius: const BorderRadius.horizontal(
                                right: Radius.circular(2),
                              ),
                              boxShadow: [
                                BoxShadow(
                                  color: aura.withValues(alpha: 0.38),
                                  blurRadius: 6,
                                  spreadRadius: 0.3,
                                ),
                                BoxShadow(
                                  color: aura.withValues(alpha: 0.18),
                                  blurRadius: 10,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
          },
        );
      },
    );
  }

  Widget _miniIconButton({
    required IconData icon,
    required VoidCallback onPressed,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 2),
      child: IconButton(
        icon: Icon(icon, color: AppTheme.textPrimary, size: 20),
        onPressed: onPressed,
        padding: const EdgeInsets.all(5),
        constraints: const BoxConstraints(),
        visualDensity: VisualDensity.compact,
        splashColor: Colors.transparent,
        highlightColor: Colors.transparent,
        style: IconButton.styleFrom(splashFactory: NoSplash.splashFactory),
      ),
    );
  }
}
