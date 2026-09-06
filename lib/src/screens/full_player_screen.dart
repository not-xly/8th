import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import '../models/song_data.dart';
import '../theme/app_theme.dart';
import '../theme/palette_service.dart';
import '../utils/artists.dart';
import '../utils/audio_formats.dart';
import '../utils/formatters.dart';
import 'lyrics_full_screen.dart';

class FullPlayerScreen extends StatefulWidget {
  final QueueState queueState;
  final AudioPlayer player;
  final void Function(int index) onGoTo;
  final void Function() onNext;
  final void Function() onPrevious;
  final Future<void> Function(int index) onRemoveFromQueue;
  final Future<void> Function(int oldIndex, int newIndex) onReorderQueue;
  final bool queueShuffled;
  final Future<void> Function(bool enable) onToggleShuffle;
  final LoopMode loopMode;
  final Future<LoopMode> Function() onCycleLoop;
  final void Function(String artist)? onArtistTap;
  const FullPlayerScreen({
    super.key,
    required this.queueState,
    required this.player,
    required this.onGoTo,
    required this.onNext,
    required this.onPrevious,
    required this.onRemoveFromQueue,
    required this.onReorderQueue,
    required this.queueShuffled,
    required this.onToggleShuffle,
    required this.loopMode,
    required this.onCycleLoop,
    this.onArtistTap,
  });
  @override
  State<FullPlayerScreen> createState() => _FullPlayerScreenState();
}

class _FullPlayerScreenState extends State<FullPlayerScreen> {
  double _sliderValue = 0;
  bool _isSliding = false;
  bool _shuffledLocal = false;
  // Dirección de la última transición de canción (1 = siguiente).
  int _switchDir = 1;
  StreamSubscription<int?>? _indexSub;
  Color _auraColor = PaletteService.auraColor.value;
  @override
  void initState() {
    super.initState();
    _shuffledLocal = widget.queueShuffled;
    _auraColor = PaletteService.auraColor.value;
    PaletteService.auraColor.addListener(_onAuraChanged);
    // Sin listener de positionStream aquí: el Slider/tiempos se actualizan
    // solo vía StreamBuilder (abajo). Escuchar además con setState
    // reconstruía toda la pantalla (portada 280px + blurs) a varios Hz.
    _indexSub = widget.player.currentIndexStream.listen((index) {
      if (!mounted) return;
      // Solo resetea el scrub manual al cambiar de tema (infrecuente).
      if (_isSliding || _sliderValue != 0) {
        setState(() {
          _isSliding = false;
          _sliderValue = 0;
        });
      }
    });
  }

  void _onAuraChanged() {
    if (!mounted) return;
    setState(() => _auraColor = PaletteService.auraColor.value);
  }

  @override
  void didUpdateWidget(covariant FullPlayerScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Si el shuffle se cambió en otra pantalla (QueueView), sincroniza el
    // toggle local en vez de quedarse desfasado.
    if (oldWidget.queueShuffled != widget.queueShuffled &&
        _shuffledLocal != widget.queueShuffled) {
      setState(() => _shuffledLocal = widget.queueShuffled);
    }
  }

  @override
  void dispose() {
    PaletteService.auraColor.removeListener(_onAuraChanged);
    _indexSub?.cancel();
    super.dispose();
  }

  // Único formatter compartido (utils/formatters.dart).
  String _fmt(Duration d) => fmtMs(d.inMilliseconds);

  void _seek(double val) {
    final dur = widget.player.duration;
    if (dur != null) {
      widget.player.seek(
        Duration(milliseconds: (dur.inMilliseconds * val).round()),
      );
    }
  }

  /// Siguiente/anterior compartido por botones y swipe horizontal.
  /// Delegan en el servicio (salto robusto ante toques rápidos: el índice
  /// de queueState se actualiza al instante y la portada/título responden
  /// al primer toque).
  void _skipNext() {
    _switchDir = 1;
    widget.onNext();
    setState(() {});
  }

  void _skipPrev() {
    _switchDir = -1;
    widget.onPrevious();
    setState(() {});
  }

  void _openLyricsFullScreen(SongData song) {
    // Transición solo de fundido (sin desplazamiento lateral)
    Navigator.push(
      context,
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 240),
        reverseTransitionDuration: const Duration(milliseconds: 200),
        opaque: true,
        pageBuilder: (_, animation2, animation3) =>
            LyricsFullScreen(song: song, player: widget.player),
        transitionsBuilder: (_, animation, animation4, child) => FadeTransition(
          opacity: CurvedAnimation(
            parent: animation,
            curve: Curves.easeInOutCubic,
          ),
          child: child,
        ),
      ),
    );
  }

  void _showQueueSheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(AppTheme.radiusL),
        ),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Stack(
          children: [
            // Zona fuera del sheet: tocar o deslizar hacia abajo cierra
            Positioned.fill(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => Navigator.pop(ctx),
                onVerticalDragEnd: (d) {
                  if (d.velocity.pixelsPerSecond.dy > 200) {
                    Navigator.pop(ctx);
                  }
                },
              ),
            ),
            Align(
              alignment: Alignment.bottomCenter,
              child: SafeArea(
                child: ClipRRect(
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(AppTheme.radiusL),
                  ),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(
                      sigmaX: AppTheme.blurSigma,
                      sigmaY: AppTheme.blurSigma,
                    ),
                    child: Container(
                      height: MediaQuery.of(context).size.height * 0.62,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: AppTheme.glassSurface.withValues(alpha: 0.78),
                        borderRadius: const BorderRadius.vertical(
                          top: Radius.circular(AppTheme.radiusL),
                        ),
                        border: Border.all(
                          color: AppTheme.glassBorder,
                          width: 0.8,
                        ),
                      ),
                      child: Column(
                        children: [
                          Center(
                            child: Container(
                              width: 36,
                              height: 4,
                              decoration: BoxDecoration(
                                color: Colors.white.withValues(alpha: 0.22),
                                borderRadius: BorderRadius.circular(999),
                              ),
                            ),
                          ),
                          const SizedBox(height: 14),
                          Row(
                            children: [
                              Builder(
                                builder: (context) {
                                  final aura = Theme.of(
                                    context,
                                  ).colorScheme.primary;
                                  return Container(
                                    width: 36,
                                    height: 36,
                                    alignment: Alignment.center,
                                    decoration: BoxDecoration(
                                      color: aura.withValues(alpha: 0.14),
                                      borderRadius: BorderRadius.circular(10),
                                      border: Border.all(
                                        color: aura.withValues(alpha: 0.16),
                                        width: 0.8,
                                      ),
                                    ),
                                    child: Icon(
                                      Icons.queue_music_rounded,
                                      color: aura,
                                      size: 18,
                                    ),
                                  );
                                },
                              ),
                              const SizedBox(width: 10),
                              const Text(
                                'Cola de reproducción',
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                  letterSpacing: -0.3,
                                  color: AppTheme.textPrimary,
                                ),
                              ),
                              const Spacer(),
                              Builder(
                                builder: (context) {
                                  final aura =
                                      Theme.of(context).colorScheme.primary;
                                  return Container(
                                    width: 36,
                                    height: 36,
                                    alignment: Alignment.center,
                                    decoration: BoxDecoration(
                                      color: _shuffledLocal
                                          ? aura.withValues(alpha: 0.14)
                                          : Colors.white.withValues(
                                              alpha: 0.06,
                                            ),
                                      borderRadius: BorderRadius.circular(10),
                                      border: Border.all(
                                        color: _shuffledLocal
                                            ? aura.withValues(alpha: 0.24)
                                            : AppTheme.glassBorder,
                                        width: 0.7,
                                      ),
                                    ),
                                    child: IconButton(
                                      icon: Icon(
                                        Icons.shuffle_rounded,
                                        size: 18,
                                        color: _shuffledLocal
                                            ? aura
                                            : AppTheme.textSecondary,
                                      ),
                                      tooltip: _shuffledLocal
                                          ? 'Restaurar orden original'
                                          : 'Aleatorizar cola',
                                      onPressed: () {
                                        final newValue = !_shuffledLocal;
                                        setState(() => _shuffledLocal = newValue);
                                        widget.onToggleShuffle(newValue);
                                        setSheetState(() {});
                                      },
                                      padding: EdgeInsets.zero,
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
                            ],
                          ),
                          const SizedBox(height: 12),
                          Divider(color: AppTheme.glassBorder, height: 1),
                          const SizedBox(height: 8),
                          Expanded(
                            child: ReorderableListView.builder(
                              itemCount: widget.queueState.list.length,
                              // Proxy de arrastre: reconstruye solo la tarjeta
                              // redondeada, sin el padding de separación inferior
                              proxyDecorator: (child, index, animation) =>
                                  AnimatedBuilder(
                                    animation: animation,
                                    builder: (context, _) => Material(
                                      color: Colors.transparent,
                                      child: Transform.scale(
                                        scale: 1.0 + 0.03 * animation.value,
                                        child: RepaintBoundary(
                                          child: _queueSheetCard(
                                            ctx,
                                            song: widget.queueState.list[index],
                                            index: index,
                                            isPlaying:
                                                index == widget.queueState.index,
                                            refresh: () {},
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                              onReorderItem: (oldIdx, newIdx) async {
                                await widget.onReorderQueue(oldIdx, newIdx);
                                setSheetState(() {});
                                setState(() {});
                              },
                              itemBuilder: (context, index) {
                                final song = widget.queueState.list[index];
                                final isPlaying =
                                    index == widget.queueState.index;
                                return Padding(
                                  // Clave por entrada de cola (uri + nº de
                                  // repetición): con "_$index" un reordenado
                                  // cambiaba TODAS las claves y reconstruía
                                  // cada fila visible.
                                  key: ValueKey(
                                    SongData.queueEntryKey(
                                        widget.queueState.list, index),
                                  ),
                                  padding: const EdgeInsets.only(bottom: 5),
                                  child: RepaintBoundary(
                                    child: _queueSheetCard(
                                      ctx,
                                      song: song,
                                      index: index,
                                      isPlaying: isPlaying,
                                      refresh: () => setSheetState(() {}),
                                    ),
                                  ),
                                );
                              },
                            ),
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
    );
  }

  Widget _queueSheetCard(
    BuildContext context, {
    required SongData song,
    required int index,
    required bool isPlaying,
    required VoidCallback refresh,
  }) {
    final aura = Theme.of(context).colorScheme.primary;
    final soft = AppTheme.desaturated(aura);
    return Container(
      decoration: BoxDecoration(
        color: isPlaying
            ? (Color.lerp(const Color(0xFF2A1A1E), aura, 0.18) ??
                    const Color(0xFF2A1A1E))
                .withValues(alpha: 0.92)
            : AppTheme.glassSurface.withValues(alpha: 0.58),
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
        border: Border.all(
          color: isPlaying
              ? aura.withValues(alpha: 0.9)
              : AppTheme.glassBorder,
          width: isPlaying ? 1.1 : 0.8,
        ),
      ),
      child: ListTile(
        splashColor: Colors.transparent,
        hoverColor: Colors.transparent,
        dense: true,
        visualDensity: const VisualDensity(vertical: -1),
        contentPadding: const EdgeInsets.only(left: 12, right: 8),
        leading: isPlaying
            ? Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(
                  color: aura.withValues(alpha: 0.14),
                  borderRadius: BorderRadius.circular(9),
                  border: Border.all(
                    color: aura.withValues(alpha: 0.22),
                    width: 0.8,
                  ),
                ),
                child: Icon(
                  Icons.equalizer_rounded,
                  color: soft,
                  size: 16,
                ),
              )
            : Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.06),
                  borderRadius: BorderRadius.circular(9),
                  border: Border.all(color: AppTheme.glassBorder, width: 0.8),
                ),
                child: Center(
                  child: Text(
                    '${index + 1}',
                    style: const TextStyle(
                      color: AppTheme.textSecondary,
                      fontSize: 11,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ),
        title: Text(
          song.displayTitle,
          style: TextStyle(
            fontSize: 13,
            fontWeight: isPlaying ? FontWeight.w600 : FontWeight.w500,
            letterSpacing: -0.2,
            color: isPlaying ? soft : AppTheme.textPrimary,
          ),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        trailing: Container(
          width: 36,
          height: 36,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.06),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: AppTheme.glassBorder, width: 0.7),
          ),
          child: IconButton(
            icon: const Icon(
              Icons.close_rounded,
              size: 18,
              color: AppTheme.textSecondary,
            ),
            onPressed: () {
              widget.onRemoveFromQueue(index).then((_) {
                if (context.mounted) refresh();
                if (mounted) setState(() {});
              });
            },
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
            splashColor: Colors.transparent,
            highlightColor: Colors.transparent,
            style: IconButton.styleFrom(splashFactory: NoSplash.splashFactory),
          ),
        ),
        onTap: () {
          widget.onGoTo(index);
          refresh();
          setState(() {});
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final qs = widget.queueState;
    if (qs.list.isEmpty) {
      return Scaffold(
        appBar: AppBar(title: const Text('Reproductor')),
        body: const Center(child: Text('Cola vacía')),
      );
    }
    final song = qs.list[qs.index.clamp(0, qs.list.length - 1)];
    return Scaffold(
      extendBodyBehindAppBar: true,
      backgroundColor: AppTheme.background,
      // Sin inputs: si se abre con el teclado aún plegándose (búsqueda
      // activa), el reencuadre del Scaffold durante esos frames hacía
      // desbordar la columna fija (aviso amarillo unos frames).
      resizeToAvoidBottomInset: false,
      appBar: PreferredSize(
        preferredSize: const Size.fromHeight(56),
        child: ClipRect(
          child: BackdropFilter(
            filter: ImageFilter.blur(
              sigmaX: AppTheme.blurSigma,
              sigmaY: AppTheme.blurSigma,
            ),
            child: AppBar(
              backgroundColor: AppTheme.glassSurface.withValues(alpha: 0.42),
              elevation: 0,
              scrolledUnderElevation: 0,
              centerTitle: true,
              title: Text(
                qs.label.isNotEmpty ? qs.label : 'Reproduciendo',
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  letterSpacing: -0.2,
                  color: AppTheme.textSecondary,
                ),
              ),
              leading: Padding(
                padding: const EdgeInsets.only(left: 10),
                child: Center(
                  // Sin blur por botón (ya lo pone el appbar completo detrás).
                  child: Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.06),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: AppTheme.glassBorder,
                        width: 0.7,
                      ),
                    ),
                    child: IconButton(
                      icon: const Icon(
                        Icons.keyboard_arrow_down_rounded,
                        color: AppTheme.textSecondary,
                        size: 18,
                      ),
                      onPressed: () => Navigator.pop(context),
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints(),
                      splashColor: Colors.transparent,
                      highlightColor: Colors.transparent,
                    ),
                  ),
                ),
              ),
              actions: [
                Padding(
                  padding: const EdgeInsets.only(right: 10),
                  child: Center(
                    child: Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.06),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: AppTheme.glassBorder,
                          width: 0.7,
                        ),
                      ),
                      child: IconButton(
                        icon: const Icon(
                          Icons.queue_music_rounded,
                          color: AppTheme.textSecondary,
                          size: 18,
                        ),
                        tooltip: 'Cola de reproducción',
                        onPressed: _showQueueSheet,
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(),
                        splashColor: Colors.transparent,
                        highlightColor: Colors.transparent,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
      body: Container(
        decoration: BoxDecoration(gradient: AppTheme.backgroundGradient),
        child: GestureDetector(
          // Decisión por velocidad. Vertical: cola arriba / cerrar abajo.
          // Horizontal: swipe izq = siguiente, der = anterior.
          onPanEnd: (details) {
            final v = details.velocity.pixelsPerSecond;
            if (v.dx.abs() > v.dy.abs()) {
              if (v.dx < -600) {
                _skipNext();
              } else if (v.dx > 600) {
                _skipPrev();
              }
              return;
            }
            if (v.dy < -700) {
              _showQueueSheet();
            } else if (v.dy > 700) {
              if (Navigator.canPop(context)) Navigator.pop(context);
            }
          },
          behavior: HitTestBehavior.opaque,
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Column(
                children: [
                  Center(
                    child: Container(
                      width: 36,
                      height: 4,
                      margin: const EdgeInsets.only(bottom: 8),
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.18),
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                  ),
                  const Spacer(),
                  // Cambio de canción con animación direccional: portada,
                  // título y artista se deslizan/funden como un solo bloque
                  // (swipe izq = entra desde la derecha; der = al revés).
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 300),
                    switchInCurve: Curves.easeOutCubic,
                    switchOutCurve: Curves.easeInCubic,
                    transitionBuilder: (child, anim) {
                      final isIncoming = child.key == ValueKey(song.playUri);
                      final dir = _switchDir.toDouble();
                      return FadeTransition(
                        opacity: anim,
                        child: SlideTransition(
                          position: Tween<Offset>(
                            begin:
                                Offset(isIncoming ? dir * 0.3 : -dir * 0.3, 0),
                            end: Offset.zero,
                          ).animate(anim),
                          child: child,
                        ),
                      );
                    },
                    child: KeyedSubtree(
                      key: ValueKey(song.playUri),
                      child: _songShowcase(song),
                    ),
                  ),
                  const SizedBox(height: 24),
                  StreamBuilder<Duration>(
                    stream: widget.player.positionStream,
                    builder: (context, posSnap) {
                      final pos = posSnap.data ?? Duration.zero;
                      final dur = widget.player.duration ?? Duration.zero;
                      return Column(
                        children: [
                          SliderTheme(
                            data: SliderTheme.of(context).copyWith(
                              trackHeight: 3.5,
                              thumbShape: const RoundSliderThumbShape(
                                enabledThumbRadius: 7,
                              ),
                              overlayShape: const RoundSliderOverlayShape(
                                overlayRadius: 14,
                              ),
                              activeTrackColor: _auraColor,
                              inactiveTrackColor: Colors.white.withValues(
                                alpha: 0.12,
                              ),
                              thumbColor: Colors.white,
                              overlayColor: _auraColor.withValues(alpha: 0.14),
                            ),
                            child: Slider(
                              value: _isSliding
                                  ? _sliderValue
                                  : (dur.inMilliseconds > 0
                                        ? (pos.inMilliseconds /
                                                  dur.inMilliseconds)
                                              .clamp(0.0, 1.0)
                                        : 0.0),
                              onChangeStart: (_) => _isSliding = true,
                              onChanged: (v) =>
                                  setState(() => _sliderValue = v),
                              onChangeEnd: (v) {
                                _isSliding = false;
                                _seek(v);
                              },
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 6),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                Text(
                                  _fmt(pos),
                                  style: const TextStyle(
                                    color: AppTheme.textSecondary,
                                    fontSize: 12,
                                    fontWeight: FontWeight.w500,
                                    fontFeatures: [
                                      FontFeature.tabularFigures(),
                                    ],
                                  ),
                                ),
                                Text(
                                  _fmt(dur),
                                  style: const TextStyle(
                                    color: AppTheme.textSecondary,
                                    fontSize: 12,
                                    fontWeight: FontWeight.w500,
                                    fontFeatures: [
                                      FontFeature.tabularFigures(),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                  const SizedBox(height: 20),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _glassControl(
                        icon: Icons.shuffle_rounded,
                        isActive: _shuffledLocal,
                        tooltip: _shuffledLocal
                            ? 'Restaurar orden original'
                            : 'Aleatorio',
                        onPressed: () {
                          final newValue = !_shuffledLocal;
                          setState(() => _shuffledLocal = newValue);
                          widget.onToggleShuffle(newValue);
                        },
                      ),
                      _glassControl(
                        icon: Icons.skip_previous_rounded,
                        size: 28,
                        onPressed: _skipPrev,
                      ),
                      StreamBuilder<PlayerState>(
                        stream: widget.player.playerStateStream,
                        builder: (context, snapshot) {
                          final playing = snapshot.data?.playing ?? false;
                          return Container(
                            decoration: BoxDecoration(
                              borderRadius: BorderRadius.circular(
                                AppTheme.radiusL,
                              ),
                              gradient: LinearGradient(
                                colors: [
                                  _auraColor,
                                  _auraColor.withValues(alpha: 0.86),
                                ],
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                              ),
                              boxShadow: [
                                BoxShadow(
                                  color: _auraColor.withValues(alpha: 0.32),
                                  blurRadius: 16,
                                  offset: const Offset(0, 6),
                                ),
                              ],
                              border: Border.all(
                                color: Colors.white.withValues(alpha: 0.14),
                                width: 0.8,
                              ),
                            ),
                            child: IconButton(
                              iconSize: 36,
                              padding: const EdgeInsets.all(14),
                              constraints: const BoxConstraints(),
                              icon: Icon(
                                playing
                                    ? Icons.pause_rounded
                                    : Icons.play_arrow_rounded,
                                color: Colors.white,
                              ),
                              onPressed: () {
                                if (playing) {
                                  widget.player.pause();
                                } else {
                                  widget.player.play();
                                }
                              },
                            ),
                          );
                        },
                      ),
                      _glassControl(
                        icon: Icons.skip_next_rounded,
                        size: 28,
                        onPressed: _skipNext,
                      ),
                      _glassControl(
                        icon: Icons.lyrics_rounded,
                        tooltip: 'Letra a pantalla completa',
                        onPressed: () => _openLyricsFullScreen(song),
                      ),
                    ],
                  ),
                  const Spacer(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Portada + título + artista como un solo bloque animable
  /// (el AnimatedSwitcher del build lo desliza/funde al cambiar de tema).
  Widget _songShowcase(SongData song) {
    final art = song.albumArt;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Center(
          child: Container(
            width: 280,
            height: 280,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(AppTheme.radiusXL),
              boxShadow: [
                BoxShadow(
                  color: _auraColor.withValues(alpha: 0.32),
                  blurRadius: 40,
                  spreadRadius: 2,
                  offset: const Offset(0, 16),
                ),
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.28),
                  blurRadius: 24,
                  offset: const Offset(0, 8),
                ),
              ],
            ),
            child: Hero(
              tag: 'playerArt_${song.playUri}',
              child: ClipRRect(
                borderRadius: BorderRadius.circular(
                  AppTheme.radiusXL,
                ),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (art != null)
                      Image.memory(
                        art,
                        width: 280,
                        height: 280,
                        fit: BoxFit.cover,
                        cacheWidth: 560,
                        gaplessPlayback: true,
                        filterQuality: FilterQuality.low,
                      )
                    else
                      Container(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              AppTheme.glassSurfaceLight.withValues(
                                alpha: 0.9,
                              ),
                              AppTheme.glassSurface.withValues(
                                alpha: 0.7,
                              ),
                            ],
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                          ),
                        ),
                        child: const Icon(
                          Icons.album_rounded,
                          size: 96,
                          color: AppTheme.textTertiary,
                        ),
                      ),
                    Positioned.fill(
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(
                            AppTheme.radiusXL,
                          ),
                          border: Border.all(
                            color: Colors.white.withValues(
                              alpha: 0.10,
                            ),
                            width: 0.9,
                          ),
                          gradient: LinearGradient(
                            colors: [
                              Colors.white.withValues(alpha: 0.08),
                              Colors.transparent,
                            ],
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
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
        const SizedBox(height: 18),
        Text(
          song.displayTitle,
          style: const TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.6,
            color: AppTheme.textPrimary,
          ),
          textAlign: TextAlign.center,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        const SizedBox(height: 6),
        InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: () => widget.onArtistTap?.call(
            Artists.primaryArtist(song.artist) ??
                song.album ??
                AppStrings.unknown,
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: 10,
              vertical: 3,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    song.artist ?? song.album ?? AppStrings.unknown,
                    style: TextStyle(
                      color: _auraColor.withValues(alpha: 0.92),
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                      letterSpacing: 0.1,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 3),
                Icon(
                  Icons.chevron_right_rounded,
                  size: 16,
                  color: _auraColor.withValues(alpha: 0.75),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _glassControl({
    required IconData icon,
    double size = 20,
    bool isActive = false,
    String? tooltip,
    required VoidCallback onPressed,
  }) {
    // Sin BackdropFilter: en un botón de 44px el blur es imperceptible y
    // cada uno fuerza un render pass offscreen (GPU/batería). Color
    // translúcido sólido, mismo look a coste cero.
    return Tooltip(
      message: tooltip ?? '',
      child: Container(
        decoration: BoxDecoration(
          color: isActive
              ? _auraColor.withValues(alpha: 0.14)
              : Colors.white.withValues(alpha: 0.06),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isActive
                ? _auraColor.withValues(alpha: 0.22)
                : AppTheme.glassBorder,
            width: 0.8,
          ),
        ),
        child: IconButton(
          icon: Icon(
            icon,
            size: size,
            color: isActive ? _auraColor : AppTheme.textSecondary,
          ),
          onPressed: onPressed,
          padding: const EdgeInsets.all(10),
          constraints: const BoxConstraints(),
          splashColor: Colors.transparent,
          highlightColor: Colors.transparent,
          style: IconButton.styleFrom(
            splashFactory: NoSplash.splashFactory,
          ),
        ),
      ),
    );
  }
}
