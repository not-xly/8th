import 'dart:async';
import 'dart:ui';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';

import '../models/song_data.dart';
import '../theme/app_theme.dart';
import '../utils/audio_formats.dart';
import '../utils/formatters.dart';
import '../widgets/lyrics_view.dart';

class LyricsFullScreen extends StatefulWidget {
  final SongData song;
  final AudioPlayer player;

  const LyricsFullScreen({super.key, required this.song, required this.player});

  @override
  State<LyricsFullScreen> createState() => _LyricsFullScreenState();
}

class _LyricsFullScreenState extends State<LyricsFullScreen> {
  late SongData _current;
  StreamSubscription<int?>? _sub;
  Offset _panOffset = Offset.zero;

  @override
  void initState() {
    super.initState();
    _current = widget.song;
    _sub = widget.player.currentIndexStream.listen((index) {
      if (index == null) return;
      try {
        final seq = widget.player.sequence;
        if (index >= 0 && index < seq.length) {
          final tag = seq[index].tag;
          if (tag is MediaItem) {
            final newSong = SongData.fromMediaItem(
              tag,
              albumArt: _current.albumArt,
            );
            if (newSong.path != _current.path ||
                newSong.displayTitle != _current.displayTitle) {
              if (mounted) setState(() => _current = newSong);
            }
            return;
          }
        }
      } catch (_) {}
      // Fallback: si no pudimos resolver, no hacemos nada; didUpdateWidget manejará si parent rebuild
    });
  }

  @override
  void didUpdateWidget(covariant LyricsFullScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.song.path != widget.song.path) {
      setState(() => _current = widget.song);
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final song = _current;
    final aura = Theme.of(context).colorScheme.primary;
    return Scaffold(
      extendBodyBehindAppBar: true,
      backgroundColor: AppTheme.background,
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
              title: Column(
                children: [
                  Text(
                    song.displayTitle,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: AppTheme.textPrimary,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    song.artist ?? song.album ?? AppStrings.unknown,
                    style: const TextStyle(
                      fontSize: 11,
                      color: AppTheme.textSecondary,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
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
                        Icons.close_rounded,
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
            ),
          ),
        ),
      ),
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        // Deslizar hacia abajo fuera de la letra vuelve al player normal
        onVerticalDragUpdate: (d) => _panOffset += d.delta,
        onVerticalDragEnd: (d) {
          final dy = _panOffset.dy;
          final v = d.velocity.pixelsPerSecond.dy;
          _panOffset = Offset.zero;
          if (dy > 60 || v > 700) {
            if (Navigator.canPop(context)) Navigator.pop(context);
          }
        },
        child: Container(
          decoration: BoxDecoration(gradient: AppTheme.backgroundGradient),
          child: SafeArea(
            child: Column(
              children: [
                const SizedBox(height: 12),
                // Arte pequeño con blur de fondo si hay
                if (song.albumArt != null)
                  Container(
                    width: 96,
                    height: 96,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(16),
                      boxShadow: [
                        BoxShadow(
                          color: aura.withValues(alpha: 0.28),
                          blurRadius: 20,
                          offset: const Offset(0, 8),
                        ),
                      ],
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(16),
                      child: Image.memory(
                        song.albumArt!,
                        fit: BoxFit.cover,
                        gaplessPlayback: true,
                      ),
                    ),
                  ),
                if (song.albumArt != null) const SizedBox(height: 16),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: _FullLyricsView(song: song, player: widget.player),
                  ),
                ),
                const SizedBox(height: 12),
                // Controles mini en full lyrics
                _FullLyricsControls(player: widget.player),
                const SizedBox(height: 16),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// Wrapper que reutiliza LyricsView pero con altura expandida
class _FullLyricsView extends StatelessWidget {
  final SongData song;
  final AudioPlayer player;
  const _FullLyricsView({required this.song, required this.player});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: double.infinity,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppTheme.glassBorder, width: 0.7),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: LyricsView(
          key: ValueKey('full_${song.path}'),
          song: song,
          player: player,
          expand: true,
        ),
      ),
    );
  }
}

class _FullLyricsControls extends StatelessWidget {
  final AudioPlayer player;
  const _FullLyricsControls({required this.player});

  String _fmt(Duration d) => fmtMs(d.inMilliseconds);

  @override
  Widget build(BuildContext context) {
    final aura = Theme.of(context).colorScheme.primary;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        children: [
          StreamBuilder<Duration>(
            stream: player.positionStream,
            builder: (context, snap) {
              final pos = snap.data ?? Duration.zero;
              final dur = player.duration ?? Duration.zero;
              final value = dur.inMilliseconds > 0
                  ? (pos.inMilliseconds / dur.inMilliseconds).clamp(0.0, 1.0)
                  : 0.0;
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
                      activeTrackColor: aura,
                      inactiveTrackColor: Colors.white.withValues(alpha: 0.12),
                      thumbColor: Colors.white,
                      overlayColor: aura.withValues(alpha: 0.14),
                    ),
                    child: Slider(
                      value: value,
                      onChanged: (v) {
                        final d = player.duration;
                        if (d != null) {
                          player.seek(
                            Duration(
                              milliseconds: (d.inMilliseconds * v).round(),
                            ),
                          );
                        }
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
                            fontSize: 11,
                            fontFeatures: [FontFeature.tabularFigures()],
                          ),
                        ),
                        Text(
                          _fmt(dur),
                          style: const TextStyle(
                            color: AppTheme.textSecondary,
                            fontSize: 11,
                            fontFeatures: [FontFeature.tabularFigures()],
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              );
            },
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              IconButton(
                icon: const Icon(
                  Icons.skip_previous_rounded,
                  color: AppTheme.textSecondary,
                  size: 28,
                ),
                onPressed: () {
                  if (player.hasPrevious) {
                    player.seek(Duration.zero, index: player.previousIndex);
                  }
                },
              ),
              const SizedBox(width: 12),
              StreamBuilder<PlayerState>(
                stream: player.playerStateStream,
                builder: (context, snap) {
                  final playing = snap.data?.playing ?? false;
                  return Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(16),
                      color: aura,
                      boxShadow: [
                        BoxShadow(
                          color: aura.withValues(alpha: 0.32),
                          blurRadius: 12,
                        ),
                      ],
                    ),
                    child: IconButton(
                      icon: Icon(
                        playing
                            ? Icons.pause_rounded
                            : Icons.play_arrow_rounded,
                        color: Colors.white,
                        size: 28,
                      ),
                      onPressed: () => playing ? player.pause() : player.play(),
                      padding: const EdgeInsets.all(12),
                      constraints: const BoxConstraints(),
                    ),
                  );
                },
              ),
              const SizedBox(width: 12),
              IconButton(
                icon: const Icon(
                  Icons.skip_next_rounded,
                  color: AppTheme.textSecondary,
                  size: 28,
                ),
                onPressed: () {
                  if (player.hasNext) {
                    player.seek(Duration.zero, index: player.nextIndex);
                  }
                },
              ),
            ],
          ),
        ],
      ),
    );
  }
}
