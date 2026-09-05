import 'package:flutter/material.dart';

import '../models/song_data.dart';
import '../theme/app_theme.dart';

class TabSelector extends StatelessWidget {
  final ViewTab currentTab;
  final int albumCount;
  final int queueLength;
  final ValueChanged<ViewTab> onTabSelected;

  const TabSelector({
    super.key,
    required this.currentTab,
    required this.albumCount,
    required this.queueLength,
    required this.onTabSelected,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Container(
        padding: const EdgeInsets.all(4),
        decoration: BoxDecoration(
          color: AppTheme.glassSurface.withValues(alpha: 0.52),
          borderRadius: BorderRadius.circular(AppTheme.radiusM),
          border: Border.all(color: AppTheme.glassBorder, width: 0.7),
        ),
        child: Row(
          children: [
            _tabPill(ViewTab.albums, 'Álbumes ($albumCount)', Icons.album_rounded),
            _tabPill(ViewTab.queue, 'Cola ($queueLength)', Icons.queue_music_rounded),
          ],
        ),
      ),
    );
  }

  Widget _tabPill(ViewTab tab, String label, IconData icon) {
    final active = currentTab == tab;
    return Expanded(
      key: ValueKey(tab),
      child: GestureDetector(
        onTap: () => onTabSelected(tab),
        behavior: HitTestBehavior.opaque,
        child: Builder(builder: (context) {
          final aura = Theme.of(context).colorScheme.primary;
          // Sombra siempre presente (transparente cuando inactivo) para que
          // AnimatedContainer interpole color en vez de saltar entre [] y [shadow],
          // que era lo que hacía que el brillo "fuera mal" al cambiar de tab.
          return AnimatedContainer(
            duration: const Duration(milliseconds: 240),
            curve: Curves.easeOutCubic,
            padding: const EdgeInsets.symmetric(vertical: 10),
            decoration: BoxDecoration(
              color: active ? aura.withValues(alpha: 0.94) : Colors.transparent,
              borderRadius: BorderRadius.circular(AppTheme.radiusS),
              border: Border.all(color: active ? aura.withValues(alpha: 0.9) : Colors.transparent, width: 0.8),
              boxShadow: [
                BoxShadow(
                  color: active ? aura.withValues(alpha: 0.22) : Colors.transparent,
                  blurRadius: 10,
                  offset: const Offset(0, 3),
                ),
              ],
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                TweenAnimationBuilder<Color?>(
                  duration: const Duration(milliseconds: 240),
                  curve: Curves.easeOutCubic,
                  tween: ColorTween(
                    begin: AppTheme.textSecondary,
                    end: active ? Colors.white : AppTheme.textSecondary,
                  ),
                  builder: (context, color, _) => Icon(icon, size: 16, color: color),
                ),
                const SizedBox(width: 7),
                Flexible(
                  child: AnimatedDefaultTextStyle(
                    duration: const Duration(milliseconds: 240),
                    curve: Curves.easeOutCubic,
                    style: TextStyle(fontSize: 13, fontWeight: active ? FontWeight.w600 : FontWeight.w500, letterSpacing: 0.2, color: active ? Colors.white : AppTheme.textSecondary),
                    child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis),
                  ),
                ),
              ],
            ),
          );
        }),
      ),
    );
  }
}
