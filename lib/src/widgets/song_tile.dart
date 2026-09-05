import 'package:flutter/material.dart';

import '../models/song_data.dart';
import '../theme/app_theme.dart';
import '../utils/formatters.dart';
import '../utils/audio_formats.dart';

class SongTile extends StatelessWidget {
  final SongData song;
  final bool isCurrent;
  final VoidCallback onTap;
  final VoidCallback onAddToNext;
  final VoidCallback onAddToEnd;

  const SongTile({
    super.key,
    required this.song,
    required this.isCurrent,
    required this.onTap,
    required this.onAddToNext,
    required this.onAddToEnd,
  });

  String _subtitle() {
    final who = song.artist ?? song.album ?? AppStrings.unknown;
    final d = song.durationMs;
    if (d != null && d > 0) {
      return '$who • ${fmtMs(d)}';
    }
    return who;
  }

  @override
  Widget build(BuildContext context) {
    final aura = Theme.of(context).colorScheme.primary;
    final auraSoft = Color.lerp(AppTheme.primarySoft, aura, 0.6) ?? aura;
    return Padding(
      padding: const EdgeInsets.only(bottom: 7),
      child: RepaintBoundary(
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 420),
          curve: Curves.easeInOutCubic,
          decoration:
              AppTheme.glassCardDecoration(
                isPlaying: isCurrent,
                radius: AppTheme.radiusM,
              ).copyWith(
                color: isCurrent
                    ? const Color(0xFF2A1D1E).withValues(alpha: 0.92)
                    : null,
                border: isCurrent
                    ? Border.all(
                        color: aura.withValues(alpha: 0.88),
                        width: 1.0,
                      )
                    : null,
              ),
          child: ListTile(
            onTap: onTap,
            splashColor: Colors.transparent,
            hoverColor: Colors.transparent,
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 12,
              vertical: 6,
            ),
            leading: ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: song.albumArt != null
                  ? Image.memory(
                      song.albumArt!,
                      width: 44,
                      height: 44,
                      fit: BoxFit.cover,
                      cacheWidth: 132,
                      filterQuality: FilterQuality.low,
                      gaplessPlayback: true,
                    )
                  : AnimatedContainer(
                      duration: const Duration(milliseconds: 420),
                      curve: Curves.easeInOutCubic,
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        color: isCurrent
                            ? aura.withValues(alpha: 0.16)
                            : AppTheme.glassSurfaceLight.withValues(alpha: 0.9),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: isCurrent
                              ? aura.withValues(alpha: 0.24)
                              : AppTheme.glassBorder,
                          width: 0.7,
                        ),
                      ),
                      child: Icon(
                        isCurrent
                            ? Icons.equalizer_rounded
                            : Icons.music_note_rounded,
                        color: isCurrent ? auraSoft : AppTheme.textSecondary,
                        size: 20,
                      ),
                    ),
            ),
            title: AnimatedDefaultTextStyle(
              duration: const Duration(milliseconds: 420),
              curve: Curves.easeInOutCubic,
              style: TextStyle(
                fontSize: 14,
                fontWeight: isCurrent ? FontWeight.w600 : FontWeight.w500,
                letterSpacing: -0.2,
                color: isCurrent ? auraSoft : AppTheme.textPrimary,
              ),
              child: Text(
                song.displayTitle,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            subtitle: Text(
              _subtitle(),
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w400,
                color: AppTheme.textSecondary,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            trailing: PopupMenuButton<String>(
              padding: EdgeInsets.zero,
              color: AppTheme.glassSurface.withValues(alpha: 0.96),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(AppTheme.radiusS),
                side: const BorderSide(color: AppTheme.glassBorder),
              ),
              onSelected: (v) {
                if (v == 'next') onAddToNext();
                if (v == 'end') onAddToEnd();
              },
              itemBuilder: (ctx) => const [
                PopupMenuItem(
                  value: 'next',
                  child: Row(
                    children: [
                      Icon(
                        Icons.playlist_play_rounded,
                        color: AppTheme.primarySoft,
                        size: 18,
                      ),
                      SizedBox(width: 8),
                      Text(
                        'Reproducir a continuación',
                        style: TextStyle(fontSize: 13),
                      ),
                    ],
                  ),
                ),
                PopupMenuItem(
                  value: 'end',
                  child: Row(
                    children: [
                      Icon(
                        Icons.queue_rounded,
                        color: AppTheme.primary,
                        size: 18,
                      ),
                      SizedBox(width: 8),
                      Text(
                        'Añadir al final de la cola',
                        style: TextStyle(fontSize: 13),
                      ),
                    ],
                  ),
                ),
              ],
              // child (en vez de icon) evita el tap-target de 48px de IconButton,
              // así el botón mide exactamente 36px como los del header
              child: Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.06),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppTheme.glassBorder, width: 0.7),
                ),
                child: const Icon(
                  Icons.more_horiz_rounded,
                  size: 18,
                  color: AppTheme.textSecondary,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
