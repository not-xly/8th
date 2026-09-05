import 'dart:ui';

import 'package:flutter/material.dart';

import '../models/song_data.dart';
import '../theme/app_theme.dart';

class AppHeader extends StatelessWidget {
  final bool isSearching;
  final bool hasSongs;
  final ViewTab currentTab;
  final bool isReorderingAlbums;
  final int selectedAlbumCount;
  final VoidCallback? onSearchToggle;
  final VoidCallback? onShuffle;
  final VoidCallback? onPlaySelected;
  final VoidCallback onPickFolder;
  final VoidCallback onShowInfo;
  final VoidCallback onToggleReorder;
  final Future<void> Function()? onSyncLyrics;
  final bool isSyncing;
  final int syncDone;
  final int syncTotal;

  const AppHeader({
    super.key,
    required this.isSearching,
    required this.hasSongs,
    required this.currentTab,
    required this.isReorderingAlbums,
    this.selectedAlbumCount = 0,
    required this.onSearchToggle,
    required this.onShuffle,
    this.onPlaySelected,
    required this.onPickFolder,
    required this.onShowInfo,
    required this.onToggleReorder,
    this.onSyncLyrics,
    this.isSyncing = false,
    this.syncDone = 0,
    this.syncTotal = 0,
  });

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(
          sigmaX: AppTheme.blurSigma,
          sigmaY: AppTheme.blurSigma,
        ),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: AppTheme.glassSurface.withValues(alpha: 0.42),
            border: Border(
              bottom: BorderSide(color: AppTheme.glassBorder, width: 0.8),
            ),
          ),
          child: Row(
            children: [
              // Liquid glass app icon — dinámico con palette progresiva
              Builder(
                builder: (context) {
                  final aura = Theme.of(context).colorScheme.primary;
                  return Container(
                    padding: const EdgeInsets.all(9),
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: [aura, aura.withValues(alpha: 0.85)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: 0.12),
                        width: 0.8,
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: aura.withValues(alpha: 0.32),
                          blurRadius: 14,
                          offset: const Offset(0, 4),
                        ),
                        BoxShadow(
                          color: Colors.black.withValues(alpha: 0.18),
                          blurRadius: 10,
                          offset: const Offset(0, 2),
                        ),
                      ],
                    ),
                    child: const Icon(
                      Icons.graphic_eq_rounded,
                      color: Colors.white,
                      size: 20,
                    ),
                  );
                },
              ),
              const SizedBox(width: 12),
              const Text(
                '8th',
                style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w600,
                  letterSpacing: -0.5,
                  color: AppTheme.textPrimary,
                ),
              ),
              const Spacer(),
              // Aleatorio de los álbumes seleccionados (aparece al seleccionar)
              if (selectedAlbumCount > 0) ...[
                _glassIconButton(
                  icon: Icons.shuffle_rounded,
                  isActive: true,
                  tooltip: 'Aleatorio de $selectedAlbumCount álbumes',
                  onPressed: onPlaySelected,
                ),
                const SizedBox(width: 10),
              ],
              _glassIconButton(
                icon: Icons.search_rounded,
                isActive: isSearching,
                tooltip: 'Buscar',
                onPressed: hasSongs ? onSearchToggle : null,
              ),
              const SizedBox(width: 10),
              _glassIconButton(
                icon: Icons.shuffle_rounded,
                tooltip: 'Aleatorio Todo',
                onPressed: hasSongs ? onShuffle : null,
              ),
              const SizedBox(width: 10),
              PopupMenuButton<String>(
                offset: const Offset(0, 44),
                padding: EdgeInsets.zero,
                color: AppTheme.glassSurface.withValues(alpha: 0.92),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(AppTheme.radiusM),
                  side: const BorderSide(color: AppTheme.glassBorder),
                ),
                onSelected: (v) {
                  if (v == 'folder') onPickFolder();
                  if (v == 'info') onShowInfo();
                  if (v == 'reorder') onToggleReorder();
                  if (v == 'syncLyrics') onSyncLyrics?.call();
                },
                itemBuilder: (context) {
                  final aura = Theme.of(context).colorScheme.primary;
                  return [
                    PopupMenuItem(
                      value: 'folder',
                      child: Row(
                        children: [
                          Icon(
                            Icons.folder_open_rounded,
                            color: aura,
                            size: 18,
                          ),
                          const SizedBox(width: 10),
                          const Text(
                            'Carpeta de música',
                            style: TextStyle(fontSize: 14),
                          ),
                        ],
                      ),
                    ),
                    PopupMenuItem(
                      value: 'syncLyrics',
                      enabled: hasSongs && onSyncLyrics != null,
                      child: Row(
                        children: [
                          Icon(
                            Icons.lyrics_rounded,
                            color: hasSongs ? aura : AppTheme.textTertiary,
                            size: 18,
                          ),
                          const SizedBox(width: 10),
                          Text(
                            'Sincronizar letras',
                            style: TextStyle(
                              fontSize: 14,
                              color: hasSongs ? null : AppTheme.textTertiary,
                            ),
                          ),
                        ],
                      ),
                    ),
                    if (hasSongs && currentTab == ViewTab.albums)
                      PopupMenuItem(
                        value: 'reorder',
                        child: Row(
                          children: [
                            Icon(
                              isReorderingAlbums
                                  ? Icons.check_rounded
                                  : Icons.swap_vert_rounded,
                              color: aura,
                              size: 18,
                            ),
                            const SizedBox(width: 10),
                            Text(
                              isReorderingAlbums
                                  ? 'Guardar orden'
                                  : 'Ordenar álbumes',
                              style: TextStyle(fontSize: 14),
                            ),
                          ],
                        ),
                      ),
                    PopupMenuItem(
                      value: 'info',
                      child: Row(
                        children: [
                          Icon(
                            Icons.info_outline_rounded,
                            color: aura,
                            size: 18,
                          ),
                          const SizedBox(width: 10),
                          const Text(
                            'Información',
                            style: TextStyle(fontSize: 14),
                          ),
                        ],
                      ),
                    ),
                  ];
                },
                // child (en vez de icon) evita el tap-target de 48px de IconButton,
                // así el botón mide exactamente 36px como los demás
                child: Builder(
                  builder: (context) {
                    final aura = Theme.of(context).colorScheme.primary;
                    return Container(
                      width: 36,
                      height: 36,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.06),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: isSyncing
                              ? aura.withValues(alpha: 0.28)
                              : AppTheme.glassBorder,
                          width: 0.7,
                        ),
                      ),
                      child: isSyncing
                          ? SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                value: syncTotal > 0
                                    ? (syncDone / syncTotal).clamp(0.0, 1.0)
                                    : null,
                                color: aura,
                              ),
                            )
                          : const Icon(
                              Icons.more_horiz_rounded,
                              color: AppTheme.textSecondary,
                              size: 18,
                            ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _glassIconButton({
    required IconData icon,
    String? tooltip,
    VoidCallback? onPressed,
    bool isActive = false,
  }) {
    final enabled = onPressed != null;
    return Builder(
      builder: (context) {
        final aura = Theme.of(context).colorScheme.primary;
        return Material(
          color: Colors.transparent,
          type: MaterialType.transparency,
          child: InkWell(
            onTap: onPressed,
            borderRadius: BorderRadius.circular(10),
            splashFactory: NoSplash.splashFactory,
            splashColor: Colors.transparent,
            highlightColor: Colors.transparent,
            overlayColor: WidgetStateProperty.all(Colors.transparent),
            child: Container(
              width: 36,
              height: 36,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: isActive
                    ? aura.withValues(alpha: 0.14)
                    : Colors.white.withValues(alpha: enabled ? 0.06 : 0.03),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: isActive
                      ? aura.withValues(alpha: 0.28)
                      : AppTheme.glassBorder,
                  width: 0.7,
                ),
              ),
              child: Icon(
                icon,
                size: 18,
                color: enabled
                    ? (isActive ? aura : AppTheme.textSecondary)
                    : AppTheme.textTertiary,
              ),
            ),
          ),
        );
      },
    );
  }
}
