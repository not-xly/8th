import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/material.dart';
import '../models/song_data.dart';
import '../theme/app_theme.dart';

class AlbumGridView extends StatefulWidget {
  final Map<String, List<SongData>> grouped;
  final List<String> sortedAlbumKeys;
  final Set<String> selectedAlbums;
  final bool isReordering;
  final void Function(String album) onToggleAlbum;
  final void Function(String album, List<SongData> songs) onOpenDetails;
  final Future<void> Function(List<String> newOrder) onReorder;
  const AlbumGridView({
    super.key,
    required this.grouped,
    required this.sortedAlbumKeys,
    required this.selectedAlbums,
    required this.isReordering,
    required this.onToggleAlbum,
    required this.onOpenDetails,
    required this.onReorder,
  });
  @override
  State<AlbumGridView> createState() => _AlbumGridViewState();
}

class _AlbumGridViewState extends State<AlbumGridView>
    with SingleTickerProviderStateMixin, AutomaticKeepAliveClientMixin {
  // Temblor estilo iOS: ping-pong continuo mientras se reordena.
  // IMPORTANTE: se crea en initState, NO como `late final = ...` perezoso.
  // El inicializador perezoso creaba el controller la primera vez que se
  // accedía; si era en dispose() (grid desactivado), createTicker miraba
  // un ancestro TickerMode ya desmontado → "Looking up a deactivated
  // widget's ancestor is unsafe" → excepción que corrompía el árbol al
  // abrir/cerrar la búsqueda o al cambiar de pestaña.
  late final AnimationController _jiggle;
  final Map<String, GlobalKey> _cellKeys = {};

  @override
  bool get wantKeepAlive => true;

  void _pruneCellKeys() {
    // Evita retener GlobalKeys de álbumes eliminados (leak + GC bloqueado).
    if (_cellKeys.length <= widget.sortedAlbumKeys.length + 20) return;
    final alive = Set<String>.of(widget.sortedAlbumKeys);
    _cellKeys.removeWhere((k, _) => !alive.contains(k));
  }

  int _columnsForWidth(double width) {
    if (width <= 500) return 5;
    if (width <= 700) return 6;
    if (width <= 1000) return 8;
    return (width / 115).floor().clamp(9, 16);
  }

  @override
  void initState() {
    super.initState();
    _jiggle = AnimationController(vsync: this, duration: const Duration(milliseconds: 300));
    if (widget.isReordering) _jiggle.repeat(reverse: true);
  }

  @override
  void didUpdateWidget(covariant AlbumGridView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isReordering != oldWidget.isReordering) {
      if (widget.isReordering) {
        _jiggle.repeat(reverse: true);
      } else {
        _jiggle.stop();
      }
    }
    _pruneCellKeys();
  }

  @override
  void dispose() {
    _jiggle.dispose();
    super.dispose();
  }

  // Desplazamiento en vivo: calcula si soltaría antes o después del centro de la celda
  void _handleMove(String draggedKey, String targetKey, DragTargetDetails details) {
    final order = widget.sortedAlbumKeys;
    final from = order.indexOf(draggedKey);
    final targetIdx = order.indexOf(targetKey);
    if (from == -1 || targetIdx == -1) return;
    final box = _cellKeys[targetKey]?.currentContext?.findRenderObject();
    if (box is! RenderBox || !box.attached) return;
    final local = box.globalToLocal(details.offset);
    final insertAfter = local.dx > box.size.width / 2;
    var to = targetIdx + (insertAfter ? 1 : 0);
    if (from < to) to--;
    if (to == from) return;
    final newOrder = List<String>.from(order)..removeAt(from)..insert(to, draggedKey);
    if (listEquals(newOrder, order)) return;
    widget.onReorder(newOrder);
  }

  @override
  Widget build(BuildContext context) {
    // Requerido por AutomaticKeepAliveClientMixin (mantiene el estado del
    // grid al deslizar a Cola y volver en el PageView).
    super.build(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final crossAxisCount = _columnsForWidth(constraints.maxWidth);
        // Ancho de celda para el feedback de arrastre
        final cellW =
            (constraints.maxWidth - 24 - (crossAxisCount - 1) * 8) / crossAxisCount;
        // Portada por álbum precalculada UNA vez por build: antes cada celda
        // re-escaneaba todas las canciones del álbum con firstWhere.
        final covers = <String, SongData>{
          for (final name in widget.sortedAlbumKeys)
            name: _firstWithArt(widget.grouped[name] ?? const <SongData>[]),
        };
        return GridView.builder(
          padding: EdgeInsets.fromLTRB(12, 12, 12, widget.isReordering ? 40 : 12),
          addAutomaticKeepAlives: false,
          addRepaintBoundaries: true,
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: crossAxisCount,
            crossAxisSpacing: 8,
            mainAxisSpacing: 8,
            childAspectRatio: 1.0,
          ),
          itemCount: widget.sortedAlbumKeys.length,
          itemBuilder: (context, index) {
            final albumName = widget.sortedAlbumKeys[index];
            if (widget.isReordering) {
              return _buildEditCell(context, albumName, covers[albumName]!, index, cellW);
            }
            return _buildNormalCell(context, albumName, covers[albumName]!, index);
          },
        );
      },
    );
  }

  SongData _firstWithArt(List<SongData> albumSongs) {
    return albumSongs.firstWhere(
      (s) => s.albumArt != null,
      orElse: () => albumSongs.first,
    );
  }

  // ---------- Modo normal ----------

  Widget _buildNormalCell(BuildContext context, String albumName, SongData coverSong, int index) {
    final isSelected = widget.selectedAlbums.contains(albumName);
    return RepaintBoundary(
      key: ValueKey(albumName),
      child: GestureDetector(
        onTap: () {
          if (widget.selectedAlbums.isNotEmpty) {
            widget.onToggleAlbum(albumName);
          } else {
            // Solo en el tap: el grid completo ya no re-scanea por celda.
            widget.onOpenDetails(
                albumName, widget.grouped[albumName] ?? <SongData>[coverSong]);
          }
        },
        onLongPress: () => widget.onToggleAlbum(albumName),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 420),
          curve: Curves.easeInOutCubic,
          decoration: BoxDecoration(
            color: isSelected
                ? const Color(0xFF2A1D1E).withValues(alpha: 0.92)
                : AppTheme.glassSurface.withValues(alpha: 0.54),
            borderRadius: BorderRadius.circular(AppTheme.radiusM),
            border: Border.all(
              color: isSelected
                  ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.88)
                  : AppTheme.glassBorder,
              width: isSelected ? 1.0 : 0.7,
            ),
            boxShadow: isSelected
                ? [
                    BoxShadow(
                      color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.14),
                      blurRadius: 8,
                      offset: const Offset(0, 3),
                    ),
                  ]
                : [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.18),
                      blurRadius: 8,
                      offset: const Offset(0, 4),
                      spreadRadius: -2,
                    ),
                  ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(AppTheme.radiusM - 1),
            child: Stack(
              children: [
                Positioned.fill(
                  // Crossfade suave cuando la portada termina de cargar
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 450),
                    switchInCurve: Curves.easeOutCubic,
                    switchOutCurve: Curves.easeInCubic,
                    transitionBuilder: (child, anim) => FadeTransition(opacity: anim, child: child),
                    // StackFit.expand: la imagen llena la celda (BoxFit.cover recorta)
                    layoutBuilder: (currentChild, previousChildren) => Stack(
                      fit: StackFit.expand,
                      alignment: Alignment.center,
                      children: [
                        ...previousChildren,
                        ?currentChild,
                      ],
                    ),
                    child: coverSong.albumArt != null
                        ? KeyedSubtree(
                            key: const ValueKey('album_art'),
                            child: Image.memory(
                              coverSong.albumArt!,
                              fit: BoxFit.cover,
                              cacheWidth: 320,
                              filterQuality: FilterQuality.low,
                              gaplessPlayback: true,
                            ),
                          )
                        : KeyedSubtree(
                            key: const ValueKey('album_placeholder'),
                            child: Container(
                              decoration: BoxDecoration(
                                color: AppTheme.glassSurfaceLight.withValues(alpha: 0.88),
                              ),
                              child: Padding(
                                padding: const EdgeInsets.all(4),
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Container(
                                      padding: const EdgeInsets.all(5),
                                      decoration: BoxDecoration(
                                        color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.10),
                                        shape: BoxShape.circle,
                                        border: Border.all(
                                          color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.14),
                                          width: 0.7,
                                        ),
                                      ),
                                      child: Icon(
                                        Icons.album_rounded,
                                        size: 16,
                                        color: Theme.of(context).colorScheme.primary,
                                      ),
                                    ),
                                    const SizedBox(height: 4),
                                    Padding(
                                      padding: const EdgeInsets.symmetric(horizontal: 4),
                                      child: Text(
                                        albumName,
                                        style: const TextStyle(
                                          fontSize: 7.5,
                                          fontWeight: FontWeight.w500,
                                          letterSpacing: 0.2,
                                          color: AppTheme.textSecondary,
                                          height: 1.1,
                                        ),
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        textAlign: TextAlign.center,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                  ),
                ),
                if (isSelected)
                  Positioned.fill(
                    child: Container(
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.58),
                        borderRadius: BorderRadius.circular(AppTheme.radiusM - 1),
                      ),
                      child: const Center(
                        child: Icon(
                          Icons.check_circle_rounded,
                          color: Colors.white,
                          size: 26,
                        ),
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

  // ---------- Modo reordenar (estilo iOS) ----------

  Widget _coverFor(SongData coverSong) {
    // StackFit.expand: la imagen llena la celda (BoxFit.cover recorta), igual que en el grid normal
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppTheme.radiusM - 1),
      child: Container(
        color: AppTheme.glassSurfaceLight.withValues(alpha: 0.88),
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (coverSong.albumArt != null)
              Image.memory(
                coverSong.albumArt!,
                fit: BoxFit.cover,
                cacheWidth: 320,
                filterQuality: FilterQuality.low,
                gaplessPlayback: true,
              )
            else
              Icon(Icons.album_rounded, size: 22, color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.7)),
          ],
        ),
      ),
    );
  }

  Widget _buildEditCell(BuildContext context, String albumName, SongData coverSong, int index, double cellW) {
    final cellKey = _cellKeys.putIfAbsent(albumName, () => GlobalKey());
    final isEvenRowItem = index.isEven;
    final cover = _coverFor(coverSong);
    return DragTarget<String>(
      key: cellKey,
      onWillAcceptWithDetails: (details) => details.data != albumName,
      onMove: (details) => _handleMove(details.data, albumName, details),
      builder: (context, candidate, rejected) {
        return Draggable<String>(
          data: albumName,
          maxSimultaneousDrags: 1,
          feedback: Transform.scale(
            scale: 1.08,
            child: Material(
              color: Colors.transparent,
              elevation: 10,
              borderRadius: BorderRadius.circular(AppTheme.radiusM),
              child: SizedBox(
                width: cellW,
                height: cellW,
                child: cover,
              ),
            ),
          ),
          childWhenDragging: Opacity(opacity: 0.3, child: cover),
          child: AnimatedBuilder(
            animation: _jiggle,
            builder: (context, _) {
              // El ángulo se calcula AQUÍ para que vibre en cada tick
              final angle = (_jiggle.value - 0.5) * 0.035 * (isEvenRowItem ? 1.0 : -1.0);
              return Transform.rotate(
                angle: angle,
                child: Container(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(AppTheme.radiusM),
                    border: Border.all(
                      color: candidate.isNotEmpty
                          ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.8)
                          : AppTheme.glassBorder,
                      width: candidate.isNotEmpty ? 1.2 : 0.7,
                    ),
                    boxShadow: const [
                      BoxShadow(color: Colors.black, blurRadius: 8, offset: Offset(0, 4), spreadRadius: -2),
                    ],
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(AppTheme.radiusM - 1),
                    child: cover,
                  ),
                ),
              );
            },
          ),
        );
      },
    );
  }
}
