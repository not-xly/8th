import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import '../models/song_data.dart';
import '../theme/app_theme.dart';
import 'song_tile.dart';
import '../utils/audio_formats.dart';

class AlbumDetailsSheet extends StatelessWidget {
  /// Espera tras cerrar el sheet antes del trabajo pesado de reproducir
  /// (setAudioSource + rebuild del grid + paleta). Sin esto, todo compite
  /// con la animación de bajada y pega tirones.
  static const playDelay = Duration(milliseconds: 200);
  final String albumName;
  final List<SongData> albumSongs;
  final String? currentSongPath;
  final String? artistName;
  final void Function(String artist)? onArtistTap;
  final void Function(String album, List<SongData> songs, int startIndex) onPlayAlbum;
  final void Function(String album, List<SongData> songs) onPlayAlbumShuffled;
  final Future<String?> Function(SongData song) onAddToNext;
  final Future<String?> Function(SongData song) onAddToEnd;

  const AlbumDetailsSheet({
    super.key,
    required this.albumName,
    required this.albumSongs,
    required this.currentSongPath,
    this.artistName,
    this.onArtistTap,
    required this.onPlayAlbum,
    required this.onPlayAlbumShuffled,
    required this.onAddToNext,
    required this.onAddToEnd,
  });

  static Future<void> show(
    BuildContext context, {
    required String albumName,
    required List<SongData> albumSongs,
    required String? currentSongPath,
    String? artistName,
    void Function(String artist)? onArtistTap,
    required void Function(String album, List<SongData> songs, int startIndex) onPlayAlbum,
    required void Function(String album, List<SongData> songs) onPlayAlbumShuffled,
    required Future<String?> Function(SongData song) onAddToNext,
    required Future<String?> Function(SongData song) onAddToEnd,
  }) {
    return showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.32),
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(AppTheme.radiusL))),
      builder: (ctx) => AlbumDetailsSheet(
        albumName: albumName,
        albumSongs: albumSongs,
        currentSongPath: currentSongPath,
        artistName: artistName,
        onArtistTap: onArtistTap,
        onPlayAlbum: onPlayAlbum,
        onPlayAlbumShuffled: onPlayAlbumShuffled,
        onAddToNext: onAddToNext,
        onAddToEnd: onAddToEnd,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: ClipRRect(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(AppTheme.radiusL)),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: AppTheme.blurSigma, sigmaY: AppTheme.blurSigma),
          child: Container(
            height: MediaQuery.of(context).size.height * 0.72,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
            decoration: BoxDecoration(
              color: AppTheme.glassSurface.withValues(alpha: 0.78),
              borderRadius: const BorderRadius.vertical(top: Radius.circular(AppTheme.radiusL)),
              border: Border.all(color: AppTheme.glassBorder, width: 0.8),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 36,
                    height: 4,
                    decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.22), borderRadius: BorderRadius.circular(999)),
                  ),
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.14),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.16), width: 0.8),
                      ),
                      child: Icon(Icons.album_rounded, color: Theme.of(context).colorScheme.primary, size: 18),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(albumName, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, letterSpacing: -0.3, color: AppTheme.textPrimary), maxLines: 1, overflow: TextOverflow.ellipsis),
                          // Artista clicable -> álbumes del artista
                          InkWell(
                            borderRadius: BorderRadius.circular(6),
                            onTap: (artistName != null && artistName!.trim().isNotEmpty && onArtistTap != null)
                                ? () {
                                    Navigator.pop(context);
                                    onArtistTap!(artistName!.trim());
                                  }
                                : null,
                            child: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 1),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(Icons.person_rounded, size: 13, color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.9)),
                                  const SizedBox(width: 4),
                                  Flexible(
                                    child: Text(
                                      artistName ?? AppStrings.unknown,
                                      style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: onArtistTap != null ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.9) : AppTheme.textSecondary),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  ),
                                  if (onArtistTap != null) ...[
                                    const SizedBox(width: 1),
                                    Icon(Icons.chevron_right_rounded, size: 14, color: Theme.of(context).colorScheme.primary),
                                  ],
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    Container(
                      decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.94), borderRadius: BorderRadius.circular(12), boxShadow: [BoxShadow(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.24), blurRadius: 10, offset: const Offset(0, 3))]),
                      child: IconButton(
                        icon: const Icon(Icons.play_arrow_rounded, size: 26, color: Colors.white),
                        tooltip: 'Reproducir orden original',
                        onPressed: () {
                          Navigator.pop(context);
                          unawaited(Future.delayed(
                            playDelay,
                            () => onPlayAlbum(albumName, albumSongs, 0),
                          ));
                        },
                        padding: const EdgeInsets.all(6),
                        constraints: const BoxConstraints(),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.08), borderRadius: BorderRadius.circular(10), border: Border.all(color: AppTheme.glassBorder, width: 0.8)),
                      child: IconButton(
                        icon: const Icon(Icons.shuffle_rounded, size: 18, color: AppTheme.textSecondary),
                        tooltip: 'Reproducir aleatorio',
                        onPressed: () {
                          Navigator.pop(context);
                          unawaited(Future.delayed(
                            playDelay,
                            () => onPlayAlbumShuffled(albumName, albumSongs),
                          ));
                        },
                        padding: const EdgeInsets.all(8),
                        constraints: const BoxConstraints(),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Divider(color: AppTheme.glassBorder, height: 1),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.only(top: 12),
                child: ListView.builder(
                  itemCount: albumSongs.length,
                  itemBuilder: (context, index) {
                    final song = albumSongs[index];
                    final isCurrent = currentSongPath != null && currentSongPath == song.playUri;
                    return SongTile(
                      song: song,
                      isCurrent: isCurrent,
                      onTap: () {
                        Navigator.pop(context);
                        unawaited(Future.delayed(
                          playDelay,
                          () => onPlayAlbum(albumName, albumSongs, index),
                        ));
                      },
                      onAddToNext: () async {
                        final msg = await onAddToNext(song);
                        if (msg != null && context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(msg), duration: const Duration(seconds: 2), behavior: SnackBarBehavior.floating, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)), backgroundColor: AppTheme.glassSurface.withValues(alpha: 0.92)),
                          );
                        }
                      },
                      onAddToEnd: () async {
                        final msg = await onAddToEnd(song);
                        if (msg != null && context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(msg), duration: const Duration(seconds: 2), behavior: SnackBarBehavior.floating, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)), backgroundColor: AppTheme.glassSurface.withValues(alpha: 0.92)),
                          );
                        }
                      },
                    );
                  },
                ),
              ),
            ),
          ],
        ),
      ),
        ),
      ),
    );
  }
}
