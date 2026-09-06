import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import '../controllers/music_player_controller.dart';
import '../models/song_data.dart';
import '../theme/app_theme.dart';
import '../utils/artists.dart';
import '../widgets/album_details_sheet.dart';
import '../widgets/album_grid_view.dart';
import '../widgets/app_header.dart';
import '../widgets/empty_state.dart';
import '../widgets/glass_notification.dart';
import '../widgets/info_dialog.dart';
import '../widgets/mini_player.dart';
import '../widgets/queue_view.dart';
import '../widgets/search_bar_widget.dart';
import '../widgets/song_tile.dart';
import '../widgets/tab_selector.dart';
import 'full_player_screen.dart';

class MusicPlayerScreen extends StatefulWidget {
  const MusicPlayerScreen({super.key});

  @override
  State<MusicPlayerScreen> createState() => _MusicPlayerScreenState();
}

class _MusicPlayerScreenState extends State<MusicPlayerScreen> {
  late final MusicPlayerController _controller;
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocus = FocusNode();
  Timer? _searchDebounce;
  // Resultado precalculado FUERA de build: antes searchResults() corría en
  // cada rebuild (normalizando toda la biblioteca otra vez).
  List<MapEntry<String, SongData>> _searchResults = const [];
  // Swipe horizontal entre Álbumes y Cola.
  final PageController _tabPages = PageController();

  @override
  void initState() {
    super.initState();
    _controller = MusicPlayerController();
    _controller.addListener(_onControllerUpdate);
    _controller.init();
  }

  void _onControllerUpdate() {
    if (!mounted) return;
    // Sincroniza la página si el tab cambió por otra vía (p. ej.
    // playSelectedShuffle fuerza Álbumes desde la Cola).
    final target = _controller.currentTab == ViewTab.albums ? 0 : 1;
    if (_tabPages.hasClients && _tabPages.page?.round() != target) {
      _tabPages.animateToPage(
        target,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOutCubic,
      );
    }
    setState(() {});
  }

  void _onSearchChanged(String v) {
    // Debounce 250ms: antes cada tecla reconstruía grid+mini con toda la lib.
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 250), () {
      // Fuentes de verdad: SOLO el texto del campo, leído AHORA (al fire).
      // Antes se confirmaba `v` (el texto de hace 250ms): si cerraste la
      // búsqueda o limpiaste el campo en esa ventana, la query quedaba con
      // un valor que ya no estaba en el campo y los resultados se
      // desincronizaban ("campo vacío, resultados de otra cosa").
      if (!mounted || !_controller.searching) return;
      final text = _searchController.text;
      _controller.updateSearchQuery(text);
      setState(() {
        _searchResults =
            text.isEmpty ? const [] : _controller.searchResults(text);
      });
    });
  }

  void _warnIfFailed() {
    final n = _controller.lastFailedCount;
    if (n > 0 && mounted) {
      GlassNotification.show(
        context,
        n == 1
            ? '1 canción no se pudo abrir (fichero movido o sin permiso)'
            : '$n canciones no se pudieron abrir (se omitieron)',
        icon: Icons.warning_amber_rounded,
        iconColor: Colors.orange.shade300,
        duration: const Duration(seconds: 3),
      );
      _controller.lastFailedCount = 0;
    }
  }

  Future<void> _playAlbum(String album, List<SongData> songs, int idx) async {
    // El sheet lo llama con 200 ms de retardo (animación de bajada): la
    // pantalla podría haberse cerrado entre medias.
    if (!mounted) return;
    await _controller.playAlbum(album, songs, startIndex: idx);
    _warnIfFailed();
  }

  Future<void> _playAlbumShuffled(String album, List<SongData> songs) async {
    if (!mounted) return;
    await _controller.playAlbumShuffled(album, songs);
    _warnIfFailed();
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _controller.removeListener(_onControllerUpdate);
    _searchController.dispose();
    _searchFocus.dispose();
    _tabPages.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _startSearch() {
    _controller.startSearch();
    // Cada búsqueda empieza limpia: reabrir con texto viejo en el campo y
    // resultados de otra query era una fuente del estado roto.
    _searchDebounce?.cancel();
    if (_searchController.text.isNotEmpty) _searchController.clear();
    _searchResults = const [];
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _searchFocus.requestFocus();
    });
  }

  void _closeSearch() {
    _searchDebounce?.cancel();
    // Unfocus antes de sacar la barra: si no, el teclado se quedaba
    // abierto sobre la pantalla principal.
    _searchFocus.unfocus();
    if (_searchController.text.isNotEmpty) _searchController.clear();
    _searchResults = const [];
    _controller.closeSearch();
  }

  Future<void> _addToQueueNext(SongData song) async {
    final msg = await _controller.addToQueueNext(song);
    if (!mounted || msg == null) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), duration: const Duration(seconds: 2)),
    );
  }

  Future<void> _addToQueueEnd(SongData song) async {
    final msg = await _controller.addToQueueEnd(song);
    if (!mounted || msg == null) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), duration: const Duration(seconds: 2)),
    );
  }

  void _openFullPlayer() {
    if (_controller.queueState.list.isEmpty) return;
    // Precache sin await para no bloquear el tap (el freeze de 0.5s era el await)
    final art = _controller.currentSong?.albumArt;
    if (art != null && art.isNotEmpty) {
      // No await — decodifica en background mientras arranca la transición
      unawaited(precacheImage(MemoryImage(art), context).catchError((_) {}));
    }
    Navigator.push(
      context,
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 200),
        reverseTransitionDuration: const Duration(milliseconds: 160),
        opaque: true,
        barrierDismissible: false,
        pageBuilder: (context, animation, secondaryAnimation) => FullPlayerScreen(
          queueState: _controller.queueState,
          player: _controller.player,
          queueShuffled: _controller.queueShuffled,
          onToggleShuffle: _controller.toggleQueueShuffle,
          loopMode: _controller.loopMode,
          onCycleLoop: _controller.cycleLoopMode,
          onArtistTap: _showArtistAlbums,
          onGoTo: (i) {
            _controller.player.seek(Duration.zero, index: i);
          },
          onNext: _controller.playNext,
          onPrevious: _controller.playPrevious,
          onRemoveFromQueue: (i) async {
            await _controller.removeFromQueue(i);
          },
          onReorderQueue: (o, n) async {
            await _controller.reorderQueue(o, n);
          },
        ),
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          // Fade + scale sutil, más barato que slide con blur
          final fade = CurvedAnimation(parent: animation, curve: Curves.easeOutCubic);
          final scale = Tween<double>(begin: 0.97, end: 1.0).animate(fade);
          return FadeTransition(
            opacity: fade,
            child: ScaleTransition(scale: scale, child: child),
          );
        },
      ),
    );
  }

  String? _albumArtist(List<SongData> songs) {
    return Artists.albumArtistFor(songs);
  }

  void _openAlbumDetailsSheet(String albumName, List<SongData> albumSongs) {
    AlbumDetailsSheet.show(
      context,
      albumName: albumName,
      albumSongs: albumSongs,
      currentSongPath: _controller.currentSong?.playUri,
      artistName: _albumArtist(albumSongs),
      onArtistTap: _showArtistAlbums,
      onPlayAlbum: _playAlbum,
      onPlayAlbumShuffled: _playAlbumShuffled,
      onAddToNext: _controller.addToQueueNext,
      onAddToEnd: _controller.addToQueueEnd,
    );
  }

  void _showArtistAlbums(String artist) {
    final aura = Theme.of(context).colorScheme.primary;
    // El álbum sale en el menú del artista principal de su PRIMER tema:
    // colaborar en otro corte no mete el álbum en tu menú.
    final wanted = Artists.parseArtists(artist);
    final displayArtist =
        wanted.isEmpty ? artist.trim() : wanted.first;
    final albums = _controller.grouped.entries
        .where((e) => Artists.albumMatchesArtist(e.value, wanted))
        .toList();
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.32),
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(AppTheme.radiusL))),
      builder: (ctx) => SafeArea(
        child: ClipRRect(
          borderRadius: const BorderRadius.vertical(top: Radius.circular(AppTheme.radiusL)),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: AppTheme.blurSigma, sigmaY: AppTheme.blurSigma),
            child: Container(
              height: MediaQuery.of(context).size.height * 0.6,
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
                          color: aura.withValues(alpha: 0.14),
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: aura.withValues(alpha: 0.16), width: 0.8),
                        ),
                        child: Icon(Icons.person_rounded, color: aura, size: 18),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(displayArtist, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, letterSpacing: -0.3, color: AppTheme.textPrimary), maxLines: 1, overflow: TextOverflow.ellipsis),
                            Text('${albums.length} ${albums.length == 1 ? 'álbum' : 'álbumes'}', style: const TextStyle(fontSize: 12, color: AppTheme.textSecondary)),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Divider(color: AppTheme.glassBorder, height: 1),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: albums.isEmpty
                          ? const Center(child: Text('Sin álbumes de este artista', style: TextStyle(color: AppTheme.textSecondary, fontSize: 14)))
                          : ListView.builder(
                              itemCount: albums.length,
                              itemBuilder: (ctx2, index) {
                                final albumName = albums[index].key;
                                final albumSongs = albums[index].value;
                                final art = albumSongs.firstWhere(
                                  (s) => s.albumArt != null,
                                  orElse: () => albumSongs.first,
                                ).albumArt;
                                return Padding(
                                  padding: const EdgeInsets.only(bottom: 6),
                                  child: Material(
                                    color: Colors.transparent,
                                    child: InkWell(
                                      borderRadius: BorderRadius.circular(AppTheme.radiusM),
                                      onTap: () {
                                        Navigator.pop(ctx);
                                        _openAlbumDetailsSheet(albumName, albumSongs);
                                      },
                                      child: Container(
                                        padding: const EdgeInsets.all(8),
                                        decoration: BoxDecoration(
                                          color: AppTheme.glassSurface.withValues(alpha: 0.58),
                                          borderRadius: BorderRadius.circular(AppTheme.radiusM),
                                          border: Border.all(color: AppTheme.glassBorder, width: 0.8),
                                        ),
                                        child: Row(
                                          children: [
                                            ClipRRect(
                                              borderRadius: BorderRadius.circular(10),
                                              child: art != null
                                                  ? Image.memory(art, width: 46, height: 46, fit: BoxFit.cover, cacheWidth: 138, filterQuality: FilterQuality.low, gaplessPlayback: true)
                                                  : Container(
                                                      width: 46,
                                                      height: 46,
                                                      decoration: BoxDecoration(color: AppTheme.glassSurfaceLight.withValues(alpha: 0.9), borderRadius: BorderRadius.circular(10), border: Border.all(color: AppTheme.glassBorder, width: 0.7)),
                                                      child: const Icon(Icons.album_rounded, color: AppTheme.textSecondary, size: 20),
                                                    ),
                                            ),
                                            const SizedBox(width: 12),
                                            Expanded(
                                              child: Column(
                                                crossAxisAlignment: CrossAxisAlignment.start,
                                                children: [
                                                  Text(albumName, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14, letterSpacing: -0.2, color: AppTheme.textPrimary), maxLines: 1, overflow: TextOverflow.ellipsis),
                                                  const SizedBox(height: 2),
                                                  Text('${albumSongs.length} ${albumSongs.length == 1 ? 'canción' : 'canciones'}', style: const TextStyle(color: AppTheme.textSecondary, fontSize: 12)),
                                                ],
                                              ),
                                            ),
                                            const Icon(Icons.chevron_right_rounded, color: AppTheme.textTertiary, size: 20),
                                          ],
                                        ),
                                      ),
                                    ),
                                  ),
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
      ),
    );
  }

  void _showInfo() {
    InfoDialog.show(
      context,
      songCount: _controller.songs.length,
      albumCount: _controller.grouped.keys.length,
      artistCount: _controller.countUniqueArtists(),
      onClearCache: _controller.clearArtCache,
    );
  }

  Widget _songListTile(SongData song, {required VoidCallback onTap}) {
    final isCurrent = _controller.currentSong?.playUri == song.playUri;
    return SongTile(
      song: song,
      isCurrent: isCurrent,
      onTap: onTap,
      onAddToNext: () => _addToQueueNext(song),
      onAddToEnd: () => _addToQueueEnd(song),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(gradient: AppTheme.backgroundGradient),
        child: SafeArea(
          child: Column(
            children: [
              _buildCustomHeader(),
              if (_controller.searching) _buildSearchBar() else _buildTabSelector(),
              Expanded(
                child: !_controller.initialized || _controller.loading
                    ? Center(child: CircularProgressIndicator(color: Theme.of(context).colorScheme.primary, strokeWidth: 2.5))
                    : _controller.songs.isEmpty
                        ? _emptyState()
                        : _controller.searching
                            ? _buildSearchResultsView()
                            : _buildTabContent(),
              ),
              AnimatedSize(
                duration: const Duration(milliseconds: 320),
                curve: Curves.easeInOutCubic,
                alignment: Alignment.bottomCenter,
                child: _controller.queueState.list.isNotEmpty ? _buildMiniPlayer() : const SizedBox.shrink(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _syncLyrics() async {
    if (_controller.songs.isEmpty) {
      if (!mounted) return;
      GlassNotification.show(context, 'No hay canciones cargadas', icon: Icons.warning_amber_rounded, iconColor: AppTheme.textSecondary);
      return;
    }
    if (_controller.lyricsSyncing) {
      // Segunda pulsación cancela (antes no había forma de parar un sync largo).
      _controller.cancelLyricsSync();
      if (!mounted) return;
      GlassNotification.show(context, 'Cancelando sincronización...', icon: Icons.cancel_rounded);
      return;
    }
    if (!mounted) return;
    GlassNotification.show(context, 'Verificando letras...', icon: Icons.search_rounded, duration: const Duration(seconds: 1));

    final result = await _controller.syncLyricsManual(
      onMissingCount: (missing) {
        if (!mounted) return;
        GlassNotification.show(context, 'Faltan $missing letras, descargando...', icon: Icons.download_rounded, duration: const Duration(seconds: 2));
      },
    );

    if (!mounted) return;
    if (result.emptyLibrary) {
      GlassNotification.show(context, 'No hay canciones cargadas', icon: Icons.warning_amber_rounded, iconColor: AppTheme.textSecondary);
    } else if (result.alreadySyncing) {
      GlassNotification.show(context, 'Ya se está sincronizando...', icon: Icons.sync_rounded);
    } else if (result.missingBefore == 0) {
      GlassNotification.show(context, 'Ya están todas las letras (${result.total}/${result.total})', icon: Icons.check_circle_rounded, iconColor: Colors.green.shade400);
    } else {
      if (result.fetched == 0) {
        GlassNotification.show(context, 'No se encontraron ${result.missingBefore} letras nuevas', icon: Icons.info_outline_rounded, iconColor: AppTheme.textSecondary);
      } else if (result.fetched == result.missingBefore) {
        GlassNotification.show(context, 'Ya están todas las letras (descargadas ${result.fetched}/${result.missingBefore})', icon: Icons.check_circle_rounded, iconColor: Colors.green.shade400);
      } else {
        GlassNotification.show(context, 'Descargadas ${result.fetched} de ${result.missingBefore} letras. Ya están todas las disponibles.', icon: Icons.check_circle_rounded, iconColor: Colors.green.shade400);
      }
    }
  }

  Widget _buildCustomHeader() {
    return AppHeader(
      isSearching: _controller.searching,
      hasSongs: _controller.songs.isNotEmpty,
      currentTab: _controller.currentTab,
      isReorderingAlbums: _controller.reorderingAlbums,
      onSearchToggle: _controller.songs.isNotEmpty ? (_controller.searching ? _closeSearch : _startSearch) : null,
      onShuffle: _controller.songs.isNotEmpty ? _controller.playShuffled : null,
      selectedAlbumCount: _controller.selectedAlbums.length,
      onPlaySelected: _controller.playSelectedShuffle,
      onPickFolder: () => _controller.pickFolder(),
      onShowInfo: _showInfo,
      onToggleReorder: () {
        final wasReordering = _controller.reorderingAlbums;
        _controller.setReordering(!wasReordering);
        if (wasReordering && _controller.albumOrderChanged && mounted) {
          _controller.albumOrderChanged = false;
          GlassNotification.show(
            context,
            'Se han reordenado los álbumes',
            icon: Icons.check_circle_rounded,
            iconColor: Colors.green.shade400,
          );
        }
      },
      onSyncLyrics: _syncLyrics,
      isSyncing: _controller.lyricsSyncing,
      syncDone: _controller.lyricsSyncedCount,
      syncTotal: _controller.lyricsTotalToSync,
    );
  }

  Widget _buildSearchBar() {
    return SearchBarWidget(
      controller: _searchController,
      focusNode: _searchFocus,
      onChanged: _onSearchChanged,
      onClear: () {
        _searchDebounce?.cancel();
        _searchController.clear();
        _searchResults = const [];
        _controller.updateSearchQuery('');
        _searchFocus.requestFocus();
      },
      onClose: _closeSearch,
    );
  }

  Widget _buildSearchResultsView() {
    // El texto del campo es la única fuente de verdad: si no coincide con
    // la query confirmada, aún no hay resultados que mostrar.
    final text = _searchController.text;
    final q = text.trim().toLowerCase();
    if (q.isEmpty) {
      return const Center(
        child: Text(
          'Escribe para buscar en tu biblioteca',
          style: TextStyle(color: Colors.white54, fontSize: 15),
        ),
      );
    }
    final results = _searchResults;
    if (results.isEmpty) {
      return Center(
        child: Text(
          'Sin resultados para "${text.trim()}"',
          style: const TextStyle(color: Colors.white54, fontSize: 15),
        ),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: results.length,
      itemBuilder: (context, index) {
        final entry = results[index];
        return _songListTile(
          entry.value,
          onTap: () {
            final albumSongs = _controller.grouped[entry.key];
            if (albumSongs == null) return;
            final idx = albumSongs.indexOf(entry.value);
            _controller.playAlbum(entry.key, albumSongs, startIndex: idx < 0 ? 0 : idx);
          },
        );
      },
    );
  }

  Widget _buildTabSelector() {
    return TabSelector(
      currentTab: _controller.currentTab,
      albumCount: _controller.grouped.length,
      queueLength: _controller.queueState.list.length,
      onTabSelected: (tab) {
        // Tap en el selector: anima la página; onPageChanged hace setTab.
        final target = tab == ViewTab.albums ? 0 : 1;
        if (_tabPages.hasClients) {
          _tabPages.animateToPage(
            target,
            duration: const Duration(milliseconds: 280),
            curve: Curves.easeOutCubic,
          );
        } else {
          _controller.setTab(tab);
        }
      },
    );
  }

  Widget _buildTabContent() {
    // PageView: deslizar horizontalmente cambia entre Álbumes y Cola.
    // En modo reordenar se desactiva el swipe para no robar el gesto
    // horizontal del arrastre de celdas.
    return PageView(
      controller: _tabPages,
      physics: _controller.reorderingAlbums
          ? const NeverScrollableScrollPhysics()
          : null,
      onPageChanged: (page) {
        _controller.setTab(page == 0 ? ViewTab.albums : ViewTab.queue);
      },
      children: [
        _buildAlbumsGridView(),
        _KeepAlive(child: _buildQueueView()),
      ],
    );
  }

  Widget _buildAlbumsGridView() {
    return AlbumGridView(
      grouped: _controller.grouped,
      sortedAlbumKeys: _controller.sortedAlbumKeys(),
      selectedAlbums: _controller.selectedAlbums,
      isReordering: _controller.reorderingAlbums,
      onToggleAlbum: _controller.toggleAlbum,
      onOpenDetails: _openAlbumDetailsSheet,
      onReorder: (newOrder) => _controller.reorderAlbums(newOrder),
    );
  }

  Widget _buildQueueView() {
    return QueueView(
      queueState: _controller.queueState,
      isShuffled: _controller.queueShuffled,
      onToggleShuffle: () => _controller.toggleQueueShuffle(!_controller.queueShuffled),
      onReorder: _controller.reorderQueue,
      onRemove: _controller.removeFromQueue,
      onTap: (index) => _controller.player.seek(Duration.zero, index: index),
    );
  }

  Widget _buildMiniPlayer() {
    // currentSong puede ser null aunque la lista no esté vacía
    // (índice transitorio fuera de rango tras borrar/reordenar).
    final song = _controller.currentSong;
    if (song == null) return const SizedBox.shrink();
    final q = _controller.queueState;
    // Peek con los índices REALES del player (respetan el shuffle nativo;
    // con índices lista±1 el peek mostraba una canción que no sonaba).
    SongData? nextSong;
    SongData? prevSong;
    if (q.list.isNotEmpty) {
      final nIdx = _controller.player.nextIndex;
      nextSong =
          (nIdx != null && nIdx >= 0 && nIdx < q.list.length) ? q.list[nIdx] : q.list.first;
      final pIdx = _controller.player.previousIndex;
      prevSong =
          (pIdx != null && pIdx >= 0 && pIdx < q.list.length) ? q.list[pIdx] : q.list.last;
    }
    return MiniPlayer(
      song: song,
      nextSong: nextSong,
      previousSong: prevSong,
      player: _controller.player,
      onTap: _openFullPlayer,
      onPrevious: _controller.playPrevious,
      onNext: _controller.playNext,
    );
  }

  Widget _emptyState() {
    return EmptyState(onPickFolder: () => _controller.pickFolder());
  }
}

/// Mantiene viva una página del PageView (no se desmonta al deslizar):
/// la cola conserva scroll y estado entre cambios de pestaña.
class _KeepAlive extends StatefulWidget {
  final Widget child;
  const _KeepAlive({required this.child});

  @override
  State<_KeepAlive> createState() => _KeepAliveState();
}

class _KeepAliveState extends State<_KeepAlive>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return widget.child;
  }
}
