package com.xly.u_8th.ui

import com.xly.u_8th.playback.withCatalogArt
import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.data.AlbumArtExtractor
import com.xly.u_8th.data.AlbumGrouper
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Playlist
import com.xly.u_8th.domain.PlaylistCodec
import com.xly.u_8th.domain.Song
import com.xly.u_8th.domain.ViewTab
import com.xly.u_8th.playback.PlaybackCatalog
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.playback.PlayerConnection
import com.xly.u_8th.ui.theme.AuraPalette
import com.xly.u_8th.ui.theme.EighthTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One-shot commands the screen translates into toasts/pickers/snackbars. */
sealed interface UiEvent {
    data class ShowNotification(
        val text: String,
        val icon: ImageVector,
        val tint: Color? = null,
        val durationMs: Long = 2000,
    ) : UiEvent

    data class ShowSnackbar(val text: String) : UiEvent
}

/**
 * Immutable UI state for the main screen (stable UI state for the main screen
 * MusicPlayerController's notifyListeners state). Audio state itself lives
 * in [PlayerBus]; this holds library/selection/search/sync state.
 */
data class MainUiState(
    val songs: List<Song> = emptyList(),
    val grouped: Map<String, List<Song>> = emptyMap(),
    val albumOrder: List<String> = emptyList(),
    /**
     * Claves ya ordenadas (albumOrder primero, luego el resto). Lo mantiene
     * el ViewModel en vez de derivarlo con un getter: la derivación creaba
     * un Set y dos listas en cada recomposición de la pantalla principal.
     */
    val sortedAlbumKeys: List<String> = emptyList(),
    val albumOrderChanged: Boolean = false,
    /** Columnas fijas del grid; null = "pantalla" (auto según el ancho). */
    val gridColumns: Int? = null,
    val playlists: List<Playlist> = emptyList(),
    /**
     * Se incrementa cada vez que llega arte nuevo. Song.equals solo compara
     * path: sin este contador, Compose ve el mapa "igual" y se salta la
     * recomposición de las celdas del grid (portadas eternamente vacías).
     */
    val artVersion: Int = 0,
    /**
     * Portadas de la caché de disco ya publicadas tras cargar la biblioteca:
     * el grid espera a esto para la entrada animada en vez de mostrar las
     * celdas vacías y verlas rellenarse una a una.
     */
    val coversReady: Boolean = false,
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val selectedAlbums: Set<String> = emptySet(),
    val currentTab: ViewTab = ViewTab.ALBUMS,
    val reordering: Boolean = false,
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchResults: SearchResults = SearchResults(),
    val lyricsSyncing: Boolean = false,
    val lyricsSyncedCount: Int = 0,
    val lyricsTotalToSync: Int = 0,
    val lastFailedCount: Int = 0,
) {
    val albumCount: Int get() = grouped.size
}

/**
 * Main screen business logic: library loading,
 * album-art enrichment, play modes, lyrics sync and search. One-shots go
 * through [events]; state through [state].
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as EighthApplication
    private val prefs = application.prefs
    private val artCache = application.artCache
    private val libraryRepository = application.libraryRepository
    private val lyricsRepository = application.lyricsRepository
    private val connection: PlayerConnection = application.playerConnection

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state

    /**
     * Un solo punto de escritura del estado: además de la actualización,
     * mantiene [MainUiState.sortedAlbumKeys] coherente con `grouped` y
     * `albumOrder` sin recalcularlo en cada recomposición de la UI. Solo se
     * recalcula si cambian las claves o el orden (no al llegar portadas), y
     * se respeta si el bloque ya lo trae calculado (fuera del hilo principal).
     */
    private inline fun updateState(crossinline block: (MainUiState) -> MainUiState) {
        _state.update { current ->
            val next = block(current)
            val stale = next.sortedAlbumKeys === current.sortedAlbumKeys && (
                next.albumOrder != current.albumOrder ||
                    (next.grouped !== current.grouped && next.grouped.keys != current.grouped.keys)
                )
            if (stale) {
                next.copy(sortedAlbumKeys = AlbumGrouper.sortedAlbumKeys(next.grouped, next.albumOrder))
            } else {
                next
            }
        }
    }

    /**
     * One-shot UI events as a buffered [Channel], not a SharedFlow.
     *
     * `MainScreen` sale de composición al navegar al reproductor completo, así
     * que un aviso emitido mientras el usuario está allí (tema que no se pudo
     * abrir…) se debe conservar. Un `SharedFlow(replay = 1)` lo resolvía mal:
     * reemite el último evento a TODO suscriptor nuevo, con lo que volver a
     * la pantalla principal re-abría el selector de archivos o repetía
     * avisos ya vistos. Un canal con búfer entrega cada evento exactamente
     * una vez y solo a un colector.
     */
    private val eventChannel = Channel<UiEvent>(capacity = EVENT_BUFFER)
    val events: Flow<UiEvent> = eventChannel.receiveAsFlow()

    private val context get() = getApplication<Application>()

    /** Bumps on every library load: cancels stale art loops. */
    private var enrichGen = 0

    private val librarySearch = LibrarySearch()

    /** Portadas pendientes de publicar en `grouped` (solo hilo principal). */
    private val pendingArt = LinkedHashMap<String, ByteArray>()
    private var artFlushJob: Job? = null
    private val artExtractor = AlbumArtExtractor(app)

    private var searchDebounceJob: Job? = null
    private var libraryLoadJob: Job? = null
    private var lyricsSyncJob: Job? = null

    @Volatile
    private var lyricsCancelRequested = false

    /** Dedup for the palette schedule (same song never scheduled twice). */
    private var lastPaletteSong: String? = null

    init {
        viewModelScope.launch {
            prefs.migrateLegacyPrefsIfNeeded()
            // Lecturas de prefs: sincronizadas y sub-milisegundo
            // (SharedPreferences es memoria tras la primera carga).
            val savedAlbumOrder = prefs.getAlbumOrder()
            val treeUri = prefs.getTreeUri()
            val savedPaths = prefs.getFilePaths()
            val savedGridColumns = prefs.getGridColumns()
            val savedPlaylists = prefs.getPlaylists()
            updateState {
                it.copy(albumOrder = savedAlbumOrder, gridColumns = savedGridColumns, playlists = savedPlaylists)
            }

            if (!treeUri.isNullOrEmpty()) {
                loadFromTreeUri(treeUri)
                return@launch
            }
            if (!savedPaths.isNullOrEmpty()) {
                loadSongs(savedPaths)
                return@launch
            }
            updateState { it.copy(initialized = true) }
        }

        // Paleta después del slide (320ms), solo si cambió la canción.
        // Con la revisión del catálogo: una cola restaurada llega sin bytes de
        // portada y el aura se quedaba en el color por defecto aunque la
        // biblioteca la aportara después.
        viewModelScope.launch {
            combine(PlayerBus.queue, PlayerBus.queueIndex, PlaybackCatalog.revision) { q, i, _ ->
                q.getOrNull(i)?.withCatalogArt()
            }
                .collect { song ->
                    val uri = song?.playUri
                    val key = uri?.let { it + (if (song.albumArt != null) "#art" else "") }
                    if (uri == null || key == lastPaletteSong) return@collect
                    lastPaletteSong = key
                    val art = song.albumArt
                    delay(320)
                    if (PlayerBus.currentSong?.playUri == uri) {
                        AuraPalette.updateForArt(art)
                    }
                }
        }

        // Resultado de playQueueList: avisa de temas que no se pudieron abrir.
        viewModelScope.launch {
            var lastSeq = 0
            PlayerBus.lastResult.collect { result ->
                if (result.seq == lastSeq) return@collect
                lastSeq = result.seq
                val failed = result.failed
                updateState { it.copy(lastFailedCount = failed) }
                if (failed > 0) {
                    eventChannel.send(
                        UiEvent.ShowNotification(
                            text = if (failed == 1) {
                                "1 canción no se pudo abrir (fichero movido o sin permiso)"
                            } else {
                                "$failed canciones no se pudieron abrir (se omitieron)"
                            },
                            icon = Icons.Rounded.WarningAmber,
                            tint = ORANGE,
                            durationMs = 3000,
                        )
                    )
                }
            }
        }
    }

    // ---- Library loading ----

    fun pickFolder(treeUri: String) {
        loadFromTreeUri(treeUri)
    }

    /** Result of the OpenMultipleDocuments picker; empty = cancelled. */
    fun loadFiles(paths: List<String>) {
        if (paths.isEmpty()) {
            // Fallback cancelado: fin del spinner.
            updateState { it.copy(loading = false, initialized = true) }
            return
        }
        loadSongs(paths)
    }

    /** [silent]: relee sin spinner (descargas nuevas en segundo plano). */
    private fun startLibraryLoad(silent: Boolean = false, block: suspend (Int) -> Unit) {
        libraryLoadJob?.cancel()
        val gen = ++enrichGen
        if (!silent) updateState { it.copy(loading = true) }
        libraryLoadJob = viewModelScope.launch {
            try {
                block(gen)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                if (gen == enrichGen) {
                    eventChannel.send(UiEvent.ShowSnackbar("No se pudo cargar la biblioteca"))
                }
            } finally {
                if (gen == enrichGen) updateState { it.copy(loading = false, initialized = true, coversReady = true) }
            }
        }
    }

    fun loadFromTreeUri(treeUri: String, silent: Boolean = false) {
        startLibraryLoad(silent) { gen ->
            val result = libraryRepository.loadFromTreeUri(treeUri)
            var songs = result.songs
            var scanned = emptyList<String>()
            if (songs.isNullOrEmpty() && result.folderScanned) {
                val dirPath = libraryRepository.resolveFolderPath(treeUri)
                scanned = dirPath?.let { libraryRepository.scanDirectoryForAudio(it) } ?: emptyList()
                if (scanned.isNotEmpty()) songs = libraryRepository.parseMetadataForPaths(scanned)
            }
            if (gen != enrichGen) return@startLibraryLoad
            if (songs.isNullOrEmpty()) {
                if (!silent) eventChannel.send(UiEvent.ShowSnackbar("No hay canciones en esa carpeta. Añade audio y vuelve a elegirla"))
                return@startLibraryLoad
            }
            prefs.setLibrarySource(treeUri, scanned)
            applyLibrary(songs, gen)
        }
    }

    fun loadSongs(paths: List<String>, persistPaths: Boolean = true) {
        startLibraryLoad { gen ->
            val newSongs = libraryRepository.parseMetadataForPaths(paths)
            if (gen != enrichGen) return@startLibraryLoad
            if (persistPaths) prefs.setLibrarySource(null, paths)
            applyLibrary(newSongs, gen)
        }
    }

    private suspend fun applyLibrary(newSongs: List<Song>, gen: Int) {
        if (gen != enrichGen) return
        // Agrupar, ordenar e indexar una biblioteca grande es CPU pura: fuera
        // del hilo principal y fuera del lambda CAS de `update` (que puede
        // reintentarse).
        val albumOrder = _state.value.albumOrder
        val (grouped, sortedKeys) = withContext(Dispatchers.Default) {
            val g = AlbumGrouper.groupByAlbum(newSongs)
            g to AlbumGrouper.sortedAlbumKeys(g, albumOrder)
        }
        if (gen != enrichGen) return
        pendingArt.clear()
        artFlushJob?.cancel()
        updateState {
            it.copy(
                songs = newSongs,
                grouped = grouped,
                coversReady = false,
                sortedAlbumKeys = if (it.albumOrder == albumOrder) {
                    sortedKeys
                } else {
                    AlbumGrouper.sortedAlbumKeys(grouped, it.albumOrder)
                },
                initialized = true,
                loading = false,
            )
        }
        val snapshot = _state.value
        withContext(Dispatchers.Default) {
            librarySearch.rebuild(snapshot.sortedAlbumKeys, snapshot.grouped, snapshot.songs.size)
        }
        // Catálogo = biblioteca actual: el panel de artista del reproductor
        // agrupa desde aquí y antes solo veía las canciones ya reproducidas.
        PlaybackCatalog.replaceAll(_state.value.grouped.values.flatten())
        restoreCachedArt(gen)
        // Antes de extraer: el enriquecimiento salta los álbumes que ya
        // tienen portada en `grouped`.
        flushArt()
        if (gen == enrichGen) updateState { it.copy(coversReady = true) }
        enrichAlbumArt(gen)
        flushArt()
    }

    // ---- Album art: disk cache + enrichment ----

    /**
     * Ejecuta [block] para cada álbum sin portada, [ART_WORKERS] a la vez: el
     * retriever es lento y en serie una biblioteca grande tarda demasiado. La
     * generación [gen] descarta trabajo obsoleto si la biblioteca se recarga
     * a mitad.
     */
    private suspend fun forEachAlbumMissingArt(gen: Int, block: suspend (key: String, albumSongs: List<Song>) -> Unit) {
        val entries = _state.value.grouped.entries.toList()
        val gate = Semaphore(ART_WORKERS)
        coroutineScope {
            for ((key, albumSongs) in entries) {
                if (gen != enrichGen || !_state.value.initialized) return@coroutineScope
                if (albumSongs.any { it.albumArt != null }) continue
                launch {
                    gate.withPermit {
                        if (gen == enrichGen) block(key, albumSongs)
                    }
                }
            }
        }
    }

    private suspend fun restoreCachedArt(gen: Int) = forEachAlbumMissingArt(gen) { key, albumSongs ->
        val cached = artCache.loadCachedAlbumArt(key, Artists.albumArtistFor(albumSongs))
        if (gen != enrichGen || cached == null) return@forEachAlbumMissingArt
        assignArt(key, cached)
        publishArtForCurrentIfIn(albumSongs, cached)
    }

    private suspend fun enrichAlbumArt(gen: Int) = forEachAlbumMissingArt(gen) { key, albumSongs ->
        val bytes = artExtractor.extract(albumSongs) { gen != enrichGen }
        if (gen != enrichGen || bytes == null) return@forEachAlbumMissingArt
        assignArt(key, bytes)
        publishArtForCurrentIfIn(albumSongs, bytes)
        artCache.cacheAlbumArt(key, bytes, Artists.albumArtistFor(albumSongs))
    }

    /** Si el tema actual es del álbum, re-publica el MediaItem y la paleta. */
    private fun publishArtForCurrentIfIn(albumSongs: List<Song>, bytes: ByteArray) {
        val curUri = PlayerBus.currentSong?.playUri ?: return
        if (albumSongs.any { it.playUri == curUri }) {
            connection.launch { refreshArt() }
            viewModelScope.launch { AuraPalette.updateForArt(bytes) }
        }
    }

    /**
     * Publica la portada de un álbum. Solo reescribe su álbum
     * (O(pistas del álbum)): tocar `songs` obligaba a mapear
     * la biblioteca entera por CADA álbum, o sea
     * O(álbumes × pistas) en la primera carga. `songs`
     * conserva las referencias sin arte (el mapa `grouped`
     * es quien lo porta); la unión para "Aleatorio Todo" se
     * hace una sola vez en [playShuffled].
     */
    private fun assignArt(albumKey: String, bytes: ByteArray) {
        val albumSongs = _state.value.grouped[albumKey] ?: return
        // El catálogo al momento: el servicio y la paleta lo leen ya.
        PlaybackCatalog.putAll(albumSongs.map { it.copy(albumArt = bytes) })
        // `grouped` por lotes: copiar el mapa por cada álbum era
        // O(álbumes²) en la primera carga.
        pendingArt[albumKey] = bytes
        if (artFlushJob?.isActive != true) {
            artFlushJob = viewModelScope.launch {
                delay(ART_FLUSH_MS)
                flushArt()
            }
        }
    }

    /** Publica de una vez en `grouped` las portadas acumuladas. */
    private fun flushArt() {
        if (pendingArt.isEmpty()) return
        val batch = pendingArt.toMap()
        pendingArt.clear()
        updateState { st ->
            var grouped: MutableMap<String, List<Song>>? = null
            for ((key, bytes) in batch) {
                val albumSongs = st.grouped[key] ?: continue
                val target = grouped ?: st.grouped.toMutableMap().also { grouped = it }
                target[key] = albumSongs.map { it.copy(albumArt = bytes) }
            }
            grouped?.let { st.copy(grouped = it, artVersion = st.artVersion + 1) } ?: st
        }
    }

    /**
     * Lista plana con portadas (O(S), una vez por llamada):
     * `songs` conserva las canciones sin arte — el mapa
     * `grouped` es quien lo porta — y "Aleatorio Todo"
     * necesita las portadas en la cola (mini-player,
     * carátula de pantalla completa).
     */
    private fun MainUiState.songsWithArt(): List<Song> {
        if (songs.isEmpty()) return songs
        val artByPath = HashMap<String, ByteArray>(songs.size * 2)
        for ((_, albumSongs) in grouped) {
            for (s in albumSongs) {
                val art = s.albumArt
                if (art != null) artByPath[s.path] = art
            }
        }
        if (artByPath.isEmpty()) return songs
        return songs.map { s ->
            if (s.albumArt != null) s else artByPath[s.path]?.let { s.copy(albumArt = it) } ?: s
        }
    }

    // ---- Play modes ----

    fun playQueueList(
        list: List<Song>,
        initialIndex: Int = 0,
        label: String,
        autoPlay: Boolean = true,
        shuffled: Boolean = false,
        resumeIfSameSong: Boolean = false,
    ) {
        if (list.isEmpty()) return
        val clamped = initialIndex.coerceIn(0, list.size - 1)
        schedulePaletteAfterSlide(list[clamped])
        connection.launch {
            playQueue(
                list,
                initialIndex = clamped,
                label = label,
                autoPlay = autoPlay,
                shuffled = shuffled,
                resumeIfSameSong = resumeIfSameSong,
            )
        }
    }

    /** Paleta DESPUÉS de la animación del mini-player (340ms). */
    private fun schedulePaletteAfterSlide(song: Song) {
        val uri = song.playUri
        if (uri == lastPaletteSong) return
        lastPaletteSong = uri
        val art = song.albumArt
        viewModelScope.launch {
            delay(340)
            if (PlayerBus.currentSong?.playUri == uri) AuraPalette.updateForArt(art)
        }
    }

    private fun playShuffledList(list: List<Song>, label: String) {
        if (list.isEmpty()) return
        val currentIndex = list.indexOfFirst { it.playUri == PlayerBus.currentSong?.playUri }
        playQueueList(
            list,
            initialIndex = if (currentIndex >= 0) currentIndex else list.indices.random(),
            label = label,
            autoPlay = if (currentIndex >= 0) PlayerBus.playing.value else true,
            resumeIfSameSong = currentIndex >= 0,
            shuffled = true,
        )
    }

    fun playShuffled() {
        playShuffledList(_state.value.songsWithArt(), "Aleatorio Todo")
    }

    fun playAlbum(albumName: String, albumSongs: List<Song>, startIndex: Int = 0) {
        playQueueList(albumSongs, initialIndex = startIndex, label = albumName)
    }

    fun playAlbumShuffled(albumName: String, albumSongs: List<Song>) {
        playShuffledList(albumSongs, "$albumName (Aleatorio)")
    }

    fun playSelectedShuffle() {
        val st = _state.value
        if (st.selectedAlbums.isEmpty()) return
        val list = st.selectedAlbums.flatMap { st.grouped[it] ?: emptyList() }
        playShuffledList(list, "Aleatorio de Selección")
        updateState { it.copy(selectedAlbums = emptySet(), currentTab = ViewTab.ALBUMS) }
    }

    // ---- Queue ----

    fun addToQueueNext(song: Song) {
        connection.launch { addNext(song) }
        eventChannel.trySend(UiEvent.ShowSnackbar("Añadida a continuación: ${song.displayTitle}"))
    }

    fun addToQueueEnd(song: Song) {
        connection.launch { addEnd(song) }
        eventChannel.trySend(UiEvent.ShowSnackbar("Añadida al final de la cola: ${song.displayTitle}"))
    }

    fun removeFromQueue(index: Int) {
        connection.launch { removeAt(index) }
    }

    /** RAW drop index (post-removal position): QueueManager adjusts internally. */
    fun reorderQueue(oldIndex: Int, newIndex: Int) {
        connection.launch { move(oldIndex, newIndex) }
    }

    fun seekToIndex(index: Int) {
        connection.launch { seekToIndex(index) }
    }

    fun toggleQueueShuffle(enable: Boolean) {
        connection.launch { setShuffled(enable) }
    }

    fun cycleLoopMode() {
        connection.launch { cycleLoop() }
    }

    // ---- Selection / tabs / search ----

    fun toggleAlbum(album: String) {
        updateState {
            val next = it.selectedAlbums.toMutableSet()
            if (!next.add(album)) next.remove(album)
            it.copy(selectedAlbums = next)
        }
    }

    fun clearSelection() {
        updateState { it.copy(selectedAlbums = emptySet()) }
    }

    fun reorderAlbums(newOrder: List<String>) {
        viewModelScope.launch {
            // Sin runCatching: se tragaría la cancelación del ViewModel.
            prefs.saveAlbumOrder(newOrder)
            updateState { it.copy(albumOrder = newOrder, albumOrderChanged = true) }
        }
    }

    /** null = "pantalla" (auto). Persistido y reflejado al instante. */
    fun setGridColumns(n: Int?) {
        viewModelScope.launch { prefs.setGridColumns(n) }
        updateState { it.copy(gridColumns = n) }
    }

    // ---- Playlists ----

    /** Crea la playlist (con [songs] si se crea desde "Añadir a playlist"). */
    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        val clean = PlaylistCodec.cleanName(name).ifEmpty { "Nueva playlist" }
        val playlist = Playlist(
            id = "pl${System.currentTimeMillis().toString(36)}",
            name = clean,
            songPaths = songs.map { it.path }.distinct(),
        )
        savePlaylists(_state.value.playlists + playlist)
        eventChannel.trySend(
            UiEvent.ShowSnackbar(
                if (songs.isEmpty()) "Playlist creada: $clean"
                else "Añadida a la nueva playlist $clean",
            ),
        )
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = PlaylistCodec.cleanName(name)
        if (clean.isEmpty()) return
        savePlaylists(_state.value.playlists.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun deletePlaylist(id: String) {
        val removed = _state.value.playlists.firstOrNull { it.id == id } ?: return
        savePlaylists(_state.value.playlists.filterNot { it.id == id })
        eventChannel.trySend(UiEvent.ShowSnackbar("Playlist eliminada: ${removed.name}"))
    }

    /** Añade al final, sin duplicar canciones que ya estaban. */
    fun addToPlaylist(id: String, songs: List<Song>) {
        val target = _state.value.playlists.firstOrNull { it.id == id } ?: return
        val present = target.songPaths.toHashSet()
        val fresh = songs.map { it.path }.distinct().filter { it !in present }
        if (fresh.isNotEmpty()) {
            savePlaylists(_state.value.playlists.map { if (it.id == id) it.copy(songPaths = it.songPaths + fresh) else it })
        }
        val text = when {
            fresh.isEmpty() -> "Ya estaba en ${target.name}"
            songs.size == 1 -> "Añadida a ${target.name}"
            else -> "${fresh.size} canciones añadidas a ${target.name}"
        }
        eventChannel.trySend(UiEvent.ShowSnackbar(text))
    }

    fun removeFromPlaylist(id: String, path: String) {
        savePlaylists(
            _state.value.playlists.map { p ->
                if (p.id == id) p.copy(songPaths = p.songPaths.filterNot { it == path }) else p
            },
        )
    }

    fun playPlaylist(playlist: Playlist, songs: List<Song>, startIndex: Int = 0) {
        playQueueList(songs, initialIndex = startIndex, label = playlist.name)
    }

    fun playPlaylistShuffled(playlist: Playlist, songs: List<Song>) {
        playShuffledList(songs, "${playlist.name} (Aleatorio)")
    }

    private fun savePlaylists(playlists: List<Playlist>) {
        updateState { it.copy(playlists = playlists) }
        viewModelScope.launch { prefs.savePlaylists(playlists) }
    }

    fun setTab(tab: ViewTab) {
        updateState { it.copy(currentTab = tab) }
    }

    fun setReordering(v: Boolean) {
        updateState { it.copy(reordering = v) }
    }

    /** La UI notificó el reordenado: evita un segundo aviso al salir de nuevo. */
    fun consumeAlbumOrderChanged() {
        updateState { it.copy(albumOrderChanged = false) }
    }

    fun startSearch() {
        updateState { it.copy(searching = true, searchQuery = "", searchResults = SearchResults()) }
    }

    fun closeSearch() {
        searchDebounceJob?.cancel()
        updateState { it.copy(searching = false, searchQuery = "", searchResults = SearchResults()) }
    }

    fun updateSearchQuery(q: String) {
        updateState { it.copy(searchQuery = q) }
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(250)
            if (!_state.value.searching) return@launch
            val grouped = _state.value.grouped
            val (songs, artists) = withContext(Dispatchers.Default) {
                librarySearch.search(q, grouped) to librarySearch.searchArtists(q)
            }
            // Primero artistas y canciones; las letras (lectura de disco la
            // primera vez) llegan después sin retrasar el resto.
            updateState { it.copy(searchResults = SearchResults(artists = artists, songs = songs)) }
            val entries = withContext(Dispatchers.Default) { librarySearch.entries() }
            val albumOf = entries.associate { (albumKey, song) -> song.path to albumKey }
            val lyricHits = lyricsRepository.searchLyrics(q, entries.map { it.second })
            if (lyricHits.isEmpty()) return@launch
            val lyrics = lyricHits.mapNotNull { (song, line) ->
                albumOf[song.path]?.let { albumKey ->
                    val withArt = grouped[albumKey]?.firstOrNull { it.path == song.path } ?: song
                    LyricHit(albumKey, withArt, line)
                }
            }
            updateState { it.copy(searchResults = it.searchResults.copy(lyrics = lyrics)) }
        }
    }

    // ---- Info / cache ----

    /** Artistas únicos normalizados (feat. no duplica; "Tyler, The Creator" es 1). */
    fun countUniqueArtists(): Int {
        val set = HashSet<String>()
        for (s in _state.value.songs) {
            for (a in Artists.parseArtists(s.artist)) {
                if (a.isNotEmpty()) set.add(Artists.normalize(a))
            }
        }
        return set.size
    }

    /** Limpieza manual de caché de portadas (regenerable). */
    fun clearArtCache(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            onResult(artCache.clearArtCache())
        }
    }

    // ---- Lyrics sync ----

    fun cancelLyricsSync() {
        lyricsCancelRequested = true
        lyricsSyncJob?.cancel()
    }

    /**
     * Sync manual desde el menú: verifica faltantes, descarga y emite el
     * resultado como notificación. Segunda pulsación mientras sincroniza
     * cancela (el menú solo llama aquí).
     */
    fun syncLyrics() {
        val songs = _state.value.songs
        if (songs.isEmpty()) {
            viewModelScope.launch {
                eventChannel.send(
                    UiEvent.ShowNotification("No hay canciones cargadas", Icons.Rounded.WarningAmber, EighthTheme.TextSecondary)
                )
            }
            return
        }
        if (_state.value.lyricsSyncing) {
            cancelLyricsSync()
            viewModelScope.launch {
                eventChannel.send(UiEvent.ShowNotification("Cancelando sincronización...", Icons.Rounded.Cancel))
            }
            return
        }
        lyricsSyncJob?.cancel()
        lyricsSyncJob = viewModelScope.launch {
            eventChannel.send(UiEvent.ShowNotification("Verificando letras...", Icons.Rounded.Search, durationMs = 1000))

            updateState { it.copy(lyricsSyncing = true, lyricsSyncedCount = 0, lyricsTotalToSync = songs.size) }
            lyricsCancelRequested = false
            try {
                lyricsRepository.purgeEmptyLrc()
                val lrcNames = lyricsRepository.cachedLrcNames()
                var missing = 0
                for (s in songs) {
                    currentCoroutineContext().ensureActive()
                    if (lyricsCancelRequested) break
                    if (!lyricsRepository.hasLrcIn(lrcNames, s)) missing++
                }
                if (lyricsCancelRequested) {
                    updateState { it.copy(lyricsSyncing = false) }
                    eventChannel.send(UiEvent.ShowNotification("Sincronización cancelada", Icons.Rounded.Cancel))
                    return@launch
                }
                if (missing == 0) {
                    updateState { it.copy(lyricsSyncing = false) }
                    eventChannel.send(
                        UiEvent.ShowNotification(
                            "Ya están todas las letras (${songs.size}/${songs.size})",
                            Icons.Rounded.CheckCircle,
                            GREEN,
                        )
                    )
                    return@launch
                }
                eventChannel.send(
                    UiEvent.ShowNotification("Faltan $missing letras, descargando...", Icons.Rounded.Download, durationMs = 2000)
                )
                val fetched = lyricsRepository.syncMissing(
                    songs,
                    onProgress = { done, total ->
                        if (done % 5 == 0 || done == total) {
                            updateState { it.copy(lyricsSyncedCount = done, lyricsTotalToSync = total) }
                        }
                    },
                    shouldCancel = { lyricsCancelRequested },
                )
                updateState { it.copy(lyricsSyncing = false) }
                if (lyricsCancelRequested) {
                    eventChannel.send(
                        UiEvent.ShowNotification(
                            "Sincronización cancelada ($fetched/$missing descargadas)",
                            Icons.Rounded.Cancel,
                        )
                    )
                    return@launch
                }
                when {
                    fetched == missing -> eventChannel.send(
                        UiEvent.ShowNotification(
                            "Ya están todas las letras (descargadas $fetched/$missing)",
                            Icons.Rounded.CheckCircle,
                            GREEN,
                        )
                    )
                    fetched > 0 -> eventChannel.send(
                        UiEvent.ShowNotification(
                            "Descargadas $fetched de $missing letras.",
                            Icons.Rounded.CheckCircle,
                            GREEN,
                        )
                    )
                    else -> eventChannel.send(
                        UiEvent.ShowNotification(
                            "No se encontraron $missing letras nuevas",
                            Icons.Rounded.Info,
                            EighthTheme.TextSecondary,
                        )
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                updateState { it.copy(lyricsSyncing = false) }
                eventChannel.send(UiEvent.ShowNotification("Sincronización cancelada", Icons.Rounded.Cancel))
            } catch (_: Exception) {
                updateState { it.copy(lyricsSyncing = false) }
            }
        }
    }

    private companion object {
        /**
         * Eventos pendientes mientras `MainScreen` no está en composición (el
         * reproductor/letras la expulsan). Con búfer lleno, `trySend` de los
         * avisos de cola se descarta; los que importan (`send`) esperan.
         */
        const val EVENT_BUFFER = 16

        /** Extracciones concurrentes de portada (retriever es costoso). */
        const val ART_WORKERS = 4

        /** Ventana para agrupar portadas antes de publicarlas en `grouped`. */
        const val ART_FLUSH_MS = 150L

        val ORANGE = Color(0xFFFFB74D) // Colors.orange.shade300
        val GREEN = Color(0xFF66BB6A) // Colors.green.shade400
    }
}
