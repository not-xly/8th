import 'dart:async';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';

import '../models/song_data.dart';
import '../services/lyrics_service.dart';
import '../theme/app_theme.dart';

class LyricsView extends StatefulWidget {
  final SongData song;
  final AudioPlayer player;
  final LyricsService? lyricsService;
  final double? height;
  final bool expand;

  const LyricsView({
    super.key,
    required this.song,
    required this.player,
    this.lyricsService,
    this.height,
    this.expand = false,
  });

  @override
  State<LyricsView> createState() => _LyricsViewState();
}

class _LyricsViewState extends State<LyricsView> {
  late LyricsService _service;
  List<LrcLine>? _lines;
  bool _loading = true;
  bool _notFound = false;
  bool _hasSync = false;
  int _currentIndex = -1;
  int _loadGen = 0;
  StreamSubscription<Duration>? _posSub;
  StreamSubscription<int?>? _indexSub;
  final ScrollController _scroll = ScrollController();
  final Map<int, GlobalKey> _keys = {};

  @override
  void initState() {
    super.initState();
    // Servicio compartido: reutiliza la resolución de directorio y la
    // sonda de escritura ya cacheadas (antes cada vista re-probaba).
    _service = widget.lyricsService ?? LyricsService.shared;
    _load();
    _posSub = widget.player.positionStream.listen(_onPosition);
    _indexSub = widget.player.currentIndexStream.listen((index) {
      if (index == null) return;
      // Intenta obtener la canción real desde el player (por si el parent no reconstruye con nuevo widget.song)
      SongData? newSong;
      try {
        final seq = widget.player.sequence;
        if (index >= 0 && index < seq.length) {
          final tag = seq[index].tag;
          if (tag is MediaItem) {
            newSong = SongData.fromMediaItem(
              tag,
              albumArt: widget.song.albumArt, // mantener art si existe
            );
            // Si el nuevo path es distinto, recargar; si es el mismo,
            // didUpdateWidget ya se encarga (evita doble fetch).
            if (newSong.playUri != widget.song.playUri) {
              _loadForSong(newSong);
              return;
            }
          }
        }
      } catch (_) {}
      // Sin MediaItem resoluble: no recargar a ciegas. didUpdateWidget
      // recargará cuando el parent reconstruya con el nuevo song. El
      // Future.delayed anterior provocaba doble _loadForSong (disco+red)
      // en cada cambio de tema.
    });
  }

  @override
  void didUpdateWidget(covariant LyricsView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.lyricsService != widget.lyricsService &&
        widget.lyricsService != null) {
      _service = widget.lyricsService!;
    }
    if (oldWidget.song.playUri != widget.song.playUri) {
      _loadForSong(widget.song);
    }
  }

  Future<void> _load() async {
    await _loadForSong(widget.song);
  }

  Future<void> _loadForSong(SongData song) async {
    final gen = ++_loadGen;
    _keys.clear();
    if (mounted) {
      setState(() {
        _loading = true;
        _notFound = false;
        _lines = null;
        _hasSync = false;
        _currentIndex = -1;
      });
    }
    try {
      var lines = await _service.loadLrc(song);
      if (gen != _loadGen) return;
      if (lines == null || lines.isEmpty) {
        // Intento fetch una sola vez si no existe
        final fetched = await _service.fetchAndSave(song);
        if (gen != _loadGen) return;
        if (fetched) {
          lines = await _service.loadLrc(song);
          if (gen != _loadGen) return;
        }
      }
      if (!mounted || gen != _loadGen) return;
      setState(() {
        _loading = false;
        if (lines == null || lines.isEmpty) {
          _notFound = true;
        } else {
          _lines = lines;
          _hasSync = lines.any((l) => l.timestamp > Duration.zero);
        }
      });
      // Calcular índice inicial
      _onPosition(widget.player.position);
      if (_hasSync) {
        // Llevar al usuario DIRECTAMENTE a la línea que suena: 1) salto
        // estimado (el item objetivo puede no estar construido aún en un
        // ListView.builder), 2) en el siguiente frame, ajuste exacto.
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted || !_hasSync || _currentIndex < 0) return;
          if (_scroll.hasClients && _currentIndex > 0) {
            final estimate =
                (_currentIndex * 38.0).clamp(0.0, _scroll.position.maxScrollExtent);
            _scroll.jumpTo(estimate);
          }
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && _hasSync) _scrollTo(_currentIndex, instant: true);
          });
        });
      }
    } catch (_) {
      if (!mounted || gen != _loadGen) return;
      setState(() {
        _loading = false;
        _notFound = true;
      });
    }
  }

  void _onPosition(Duration pos) {
    if (_lines == null || _lines!.isEmpty) return;
    // Si no hay timestamps (plain), no hay sync (cacheado por carga,
    // antes se recorría la lista en cada tick de posición).
    if (!_hasSync) return;
    int idx = -1;
    // Binary search: último con timestamp <= pos
    int lo = 0, hi = _lines!.length - 1;
    while (lo <= hi) {
      final mid = (lo + hi) >> 1;
      if (_lines![mid].timestamp <= pos) {
        idx = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (idx != _currentIndex && mounted) {
      setState(() => _currentIndex = idx);
      _scrollTo(idx);
    }
  }

  void _scrollTo(int idx, {bool instant = false}) {
    if (!_scroll.hasClients || idx < 0) return;
    final key = _keys[idx];
    if (key?.currentContext != null) {
      Scrollable.ensureVisible(
        key!.currentContext!,
        duration: instant ? Duration.zero : const Duration(milliseconds: 320),
        curve: Curves.easeInOutCubic,
        alignment: 0.4,
      );
    }
  }

  @override
  void dispose() {
    _posSub?.cancel();
    _indexSub?.cancel();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Altura adaptativa: si expand=true, ocupa todo el alto disponible (para pantalla completa)
    final bool isFull = widget.expand;
    if (_loading) {
      return Container(
        height: isFull ? double.infinity : (widget.height ?? 92),
        margin: EdgeInsets.symmetric(horizontal: isFull ? 0 : 8),
        decoration: isFull
            ? null
            : BoxDecoration(
                color: Colors.white.withValues(alpha: 0.04),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppTheme.glassBorder, width: 0.7),
              ),
        child: const Center(child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.textSecondary))),
      );
    }
    if (_notFound || _lines == null) {
      return Container(
        height: isFull ? double.infinity : 72,
        margin: EdgeInsets.symmetric(horizontal: isFull ? 0 : 8),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.04),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppTheme.glassBorder, width: 0.7),
        ),
        child: const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.lyrics_rounded, size: 18, color: AppTheme.textTertiary),
              SizedBox(height: 4),
              Text('Letra no disponible', style: TextStyle(color: AppTheme.textTertiary, fontSize: 12)),
              Text('Se buscará automáticamente', style: TextStyle(color: AppTheme.textTertiary, fontSize: 10)),
            ],
          ),
        ),
      );
    }

    final hasSync = _hasSync;
    if (!hasSync) {
      // Plain lyrics sin timestamps: mostrar scroll simple
      return Container(
        height: isFull ? double.infinity : (widget.height ?? 120),
        margin: EdgeInsets.symmetric(horizontal: isFull ? 0 : 4),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.04),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppTheme.glassBorder, width: 0.7),
        ),
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Text(
            _lines!.map((e) => e.text).join('\n'),
            style: const TextStyle(color: AppTheme.textSecondary, fontSize: 13, height: 1.5),
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    // Synced
    return Container(
      height: isFull ? double.infinity : (widget.height ?? 148),
      margin: EdgeInsets.symmetric(horizontal: isFull ? 0 : 4),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.glassBorder, width: 0.7),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(14),
        child: Stack(
          children: [
            ListView.builder(
              controller: _scroll,
              padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 14),
              itemCount: _lines!.length,
              itemBuilder: (context, i) {
                final line = _lines![i];
                final isCurrent = i == _currentIndex;
                final isPast = i < _currentIndex;
                _keys.putIfAbsent(i, () => GlobalKey());
                return GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () {
                    // Tap en frase -> seek a ese punto de la canción
                    widget.player.seek(line.timestamp);
                  },
                    child: AnimatedContainer(
                      key: _keys[i],
                      duration: const Duration(milliseconds: 280),
                      curve: Curves.easeOutCubic,
                      margin: const EdgeInsets.symmetric(vertical: 3),
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                        color: isCurrent ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.14) : Colors.transparent,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: isCurrent ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.22) : Colors.transparent,
                          width: 0.7,
                        ),
                      ),
                      child: AnimatedScale(
                        // Énfasis SOLO con escala (paint-only, no reflow):
                        // tamaño y weight fijos para todas las líneas, así
                        // la actual no re-envuelve palabras ni parpadea al
                        // cambiar (antes fontSize 13→14 + w400→w600 cambiaba
                        // los saltos de línea en cada frase).
                        scale: isCurrent ? 1.05 : 1.0,
                        duration: const Duration(milliseconds: 220),
                        curve: Curves.easeOutCubic,
                        child: AnimatedDefaultTextStyle(
                          duration: const Duration(milliseconds: 220),
                          style: TextStyle(
                            color: isCurrent
                                ? Colors.white
                                : (isPast ? AppTheme.textSecondary : AppTheme.textTertiary),
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            height: 1.35,
                          ),
                          child: Text(textAlign: TextAlign.center, line.text),
                        ),
                      ),
                    ),
                );
              },
            ),
            // Fade top/bottom
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              height: 18,
              child: IgnorePointer(
                child: Container(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [AppTheme.glassSurface.withValues(alpha: 0.9), Colors.transparent],
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                    ),
                  ),
                ),
              ),
            ),
            Positioned(
              bottom: 0,
              left: 0,
              right: 0,
              height: 18,
              child: IgnorePointer(
                child: Container(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [Colors.transparent, AppTheme.glassSurface.withValues(alpha: 0.9)],
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
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
}
