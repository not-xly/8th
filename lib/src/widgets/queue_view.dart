import 'dart:ui';
import 'package:flutter/material.dart';
import '../models/song_data.dart';
import '../theme/app_theme.dart';
import '../utils/formatters.dart';
import '../utils/audio_formats.dart';

class QueueView extends StatelessWidget {
  final QueueState queueState;
  final bool isShuffled;
  final VoidCallback onToggleShuffle;
  final Future<void> Function(int oldIndex, int newIndex) onReorder;
  final Future<void> Function(int index) onRemove;
  final void Function(int index) onTap;
  const QueueView({
    super.key,
    required this.queueState,
    required this.isShuffled,
    required this.onToggleShuffle,
    required this.onReorder,
    required this.onRemove,
    required this.onTap,
  });
  String _subtitleFor(SongData song) {
    final who = song.artist ?? song.album ?? AppStrings.unknown;
    final d = song.durationMs;
    if (d != null && d > 0) {
      return '$who • ${fmtMs(d)}';
    }
    return who;
  }

  Widget _songCard(
    BuildContext context,
    SongData song,
    int index,
    bool isPlaying,
  ) {
    final aura = Theme.of(context).colorScheme.primary;
    final auraSoft = AppTheme.desaturated(aura);
    // highlightColor transparente: sin resaltado cuadrado
    // que sobresalga de las esquinas redondeadas de la tarjeta
    return Theme(
      data: Theme.of(context).copyWith(highlightColor: Colors.transparent),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 420),
        curve: Curves.easeInOutCubic,
        decoration: isPlaying
            ? BoxDecoration(
                color: (Color.lerp(const Color(0xFF2A1D1E), aura, 0.18) ??
                        const Color(0xFF2A1D1E))
                    .withValues(alpha: 0.92),
                borderRadius: BorderRadius.circular(AppTheme.radiusM),
                border: Border.all(
                  color: aura.withValues(alpha: 0.88),
                  width: 1.0,
                ),
                boxShadow: [
                  BoxShadow(
                    color: aura.withValues(alpha: 0.14),
                    blurRadius: 10,
                    offset: const Offset(0, 3),
                  ),
                ],
              )
            : AppTheme.glassDecoration(radius: AppTheme.radiusM),
        child: ListTile(
          splashColor: Colors.transparent,
          hoverColor: Colors.transparent,
          // Derecha más pegada al borde para acercar el tirador
          contentPadding: const EdgeInsets.only(left: 16, right: 8),
          dense: true,
          visualDensity: const VisualDensity(vertical: -1),
          leading: isPlaying
              ? AnimatedContainer(
                  duration: const Duration(milliseconds: 420),
                  curve: Curves.easeInOutCubic,
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: aura.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(9),
                    border: Border.all(
                      color: aura.withValues(alpha: 0.22),
                      width: 0.7,
                    ),
                  ),
                  child: Icon(
                    Icons.equalizer_rounded,
                    color: auraSoft,
                    size: 16,
                  ),
                )
              : Container(
                  width: 28,
                  height: 28,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.06),
                    borderRadius: BorderRadius.circular(9),
                    border: Border.all(
                      color: AppTheme.glassBorder,
                      width: 0.7,
                    ),
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
          title: AnimatedDefaultTextStyle(
            duration: const Duration(milliseconds: 420),
            curve: Curves.easeInOutCubic,
            style: TextStyle(
              fontSize: 14,
              fontWeight: isPlaying ? FontWeight.w600 : FontWeight.w500,
              letterSpacing: -0.2,
              color: isPlaying ? auraSoft : AppTheme.textPrimary,
            ),
            child: Text(
              song.displayTitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          subtitle: Text(
            _subtitleFor(song),
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w400,
              color: AppTheme.textSecondary,
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
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
                    size: 18,
                    color: AppTheme.textSecondary,
                  ),
                  onPressed: () => onRemove(index),
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                  splashColor: Colors.transparent,
                  highlightColor: Colors.transparent,
                  style: IconButton.styleFrom(
                    splashFactory: NoSplash.splashFactory,
                  ),
                ),
              ),
              const SizedBox(width: 6),
              ReorderableDragStartListener(
                index: index,
                child: const Icon(
                  Icons.drag_handle_rounded,
                  color: AppTheme.textTertiary,
                  size: 16,
                ),
              ),
            ],
          ),
          onTap: () => onTap(index),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (queueState.list.isEmpty) {
      return const Center(
        child: Text(
          'Cola vacía',
          style: TextStyle(
            color: AppTheme.textSecondary,
            fontSize: 15,
            fontWeight: FontWeight.w400,
          ),
        ),
      );
    }
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: BackdropFilter(
              filter: ImageFilter.blur(
                sigmaX: AppTheme.blurSigmaSoft,
                sigmaY: AppTheme.blurSigmaSoft,
              ),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: AppTheme.glassSurface.withValues(alpha: 0.56),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: AppTheme.glassBorder, width: 0.7),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        queueState.label.isNotEmpty
                            ? queueState.label
                            : 'Cola actual',
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          letterSpacing: -0.2,
                          color: AppTheme.textPrimary,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Builder(
                      builder: (context) {
                        final aura = Theme.of(context).colorScheme.primary;
                        return AnimatedContainer(
                          duration: const Duration(milliseconds: 420),
                          curve: Curves.easeInOutCubic,
                          width: 36,
                          height: 36,
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: isShuffled
                                ? aura.withValues(alpha: 0.14)
                                : Colors.white.withValues(alpha: 0.06),
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(
                              color: isShuffled
                                  ? aura.withValues(alpha: 0.24)
                                  : AppTheme.glassBorder,
                              width: 0.7,
                            ),
                          ),
                          child: IconButton(
                            icon: Icon(
                              Icons.shuffle_rounded,
                              size: 18,
                              color: isShuffled ? aura : AppTheme.textSecondary,
                            ),
                            tooltip: isShuffled
                                ? 'Restaurar orden original'
                                : 'Aleatorizar cola',
                            onPressed: onToggleShuffle,
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
              ),
            ),
          ),
        ),
        Expanded(
          child: ReorderableListView.builder(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            buildDefaultDragHandles: false,
            // Proxy de arrastre: reconstruye solo la tarjeta, sin el padding
            // de separación inferior (para no arrastrar el hueco entre filas)
            proxyDecorator: (child, index, animation) => AnimatedBuilder(
              animation: animation,
              builder: (context, _) {
                final song = queueState.list[index];
                return Material(
                  color: Colors.transparent,
                  child: Transform.scale(
                    scale: 1.0 + 0.03 * animation.value,
                    child: RepaintBoundary(
                      child: _songCard(
                        context,
                        song,
                        index,
                        index == queueState.index,
                      ),
                    ),
                  ),
                );
              },
            ),
            itemCount: queueState.list.length,
            onReorderItem: (oldIndex, newIndex) async {
              await onReorder(oldIndex, newIndex);
            },
            itemBuilder: (context, index) {
              final song = queueState.list[index];
              final isPlaying = index == queueState.index;
              return Padding(
                // Clave por entrada de cola (uri + nº de repetición): el
                // mismo tema puede estar 2x en cola (addToQueue permite
                // duplicados) y ValueKey(path) petaba ReorderableListView
                // por claves duplicadas. Con "_$index" un reordenado
                // cambiaba todas las claves y reconstruía cada fila.
                key: ValueKey(SongData.queueEntryKey(queueState.list, index)),
                padding: const EdgeInsets.only(bottom: 5),
                // Mantener pulsada una canción también inicia el arrastre
                // (como en el sheet de cola), además del tirador
                child: ReorderableDelayedDragStartListener(
                  index: index,
                  child: RepaintBoundary(
                    child: _songCard(context, song, index, isPlaying),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
