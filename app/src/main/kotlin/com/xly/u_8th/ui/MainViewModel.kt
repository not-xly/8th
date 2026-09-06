package com.xly.u_8th.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import com.xly.u_8th.data.AlbumGrouper
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import com.xly.u_8th.domain.ViewTab
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.playback.PlayerConnection
import com.xly.u_8th.ui.theme.AuraPalette
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.util.normSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** One-shot commands the screen translates into toasts/pickers/snackbars. */
sealed interface UiEvent {
    data class ShowNotification(
        val text: String,
        val icon: ImageVector,
        val tint: Color? = null,
        val durationMs: Long = 2000,
    ) : UiEvent

    data class ShowSnackbar(val text: String) : UiEvent

    /** The current library source failed: the UI must launch the SAF file picker. */
    data object OpenFilePicker : UiEvent
}

/**
 * Immutable UI state for the main screen (port of the Flutter
 * MusicPlayerController's notifyListeners state). Audio state itself lives
 * in [PlayerBus]; this holds library/selection/search/sync state.
 */
data class MainUiState(
    val songs: List<Song> = emptyList(),
    val grouped: Map<String, List<Song>> = emptyMap(),
    val albumOrder: List<String> = emptyList(),
    val albumOrderChanged: Boolean = false,
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val selectedAlbums: Set<String> = emptySet(),
    val currentTab: ViewTab = ViewTab.ALBUMS,
    val reordering: Boolean = false,
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Pair<String, Song>> = emptyList(),
    val lyricsSyncing: Boolean = false,
    val lyricsSyncedCount: Int = 0,
    val lyricsTotalToSync: Int = 0,
    val lastFailedCount: Int = 0,
) {
    val sortedAlbumKeys: List<String>
        get() = AlbumGrouper.sortedAlbumKeys(grouped, albumOrder)

    val albumCount: Int get() = grouped.size
}

/**
 * Port of lib/src/controllers/music_player_controller.dart: library loading,
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

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events

    private val context get() = getApplication<Application>()

    /** Bumps on every library load: cancels stale art loops. */
    private var enrichGen = 0

    private var searchIndex: List<SearchEntry> = emptyList()

    private var searchDebounceJob: Job? = null

    @Volatile
    private var lyricsCancelRequested = false

    /** Dedup for the palette schedule (same song never scheduled twice). */
    private var lastPaletteSong: String? = null

    private data class SearchEntry(
        val song: Song,
        val albumKey: String,
        val title: String,
        val artist: String,
        val album: String,
    )

    init {
        viewModelScope.launch {
            prefs.migrateFromFlutterIfNeeded()
            // Lecturas de prefs en paralelo (port del controller.init).
            val albumOrder = async { prefs.getAlbumOrder() }
            val treeUri = async { prefs.getTreeUri() }
            val filePaths = async { prefs.getFilePaths() }
            _state.update { it.copy(albumOrder = albumOrder.await()) }

            val tree = treeUri.await()
            if (!tree.isNullOrEmpty()) {
                loadFromTreeUri(tree)
                return@launch
            }
            val savedPaths = filePaths.await()
            if (!savedPaths.isNullOrEmpty()) {
                loadSongs(savedPaths)
                return@launch
            }
            _state.update { it.copy(initialized = true) }
        }

        // Paleta después del slide (320ms), solo si cambió la canción.
        viewModelScope.launch {
            combine(PlayerBus.queue, PlayerBus.queueIndex) { q, i -> q.getOrNull(i) }
                .collect { song ->
                    val uri = song?.playUri
                    if (uri == null || uri == lastPaletteSong) return@collect
                    lastPaletteSong = uri
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
                _state.update { it.copy(lastFailedCount = failed) }
                if (failed > 0) {
                    _events.emit(
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
        // Persisted here (the picker only returns the URI): next launch
        // restores the library without re-asking. The file-paths mode is
        // cleared so a stale source never wins silently.
        viewModelScope.launch {
            runCatching { prefs.setTreeUri(treeUri) }
            runCatching { prefs.setFilePaths(emptyList()) }
            loadFromTreeUri(treeUri)
        }
    }

    /** Result of the OpenMultipleDocuments picker; empty = cancelled. */
    fun loadFiles(paths: List<String>) {
        if (paths.isEmpty()) {
            // Fallback cancelado (port de fallbackFilePick): fin del spinner.
            _state.update { it.copy(loading = false, initialized = true) }
            return
        }
        // El picker de ficheros sustituye a la carpeta: sin treeUri el
        // arranque siguiente no reintenta la vía fallida (port pickFiles).
        viewModelScope.launch { runCatching { prefs.clearTreeUri() } }
        loadSongs(paths)
    }

    fun loadFromTreeUri(treeUri: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = libraryRepository.loadFromTreeUri(treeUri)
            val songs = result.songs
            if (songs.isNullOrEmpty()) {
                // treeUri resuelto a carpeta real: escaneo directo (scoped
                // storage deja MediaStore/SAF vacíos).
                if (result.folderScanned) {
                    val dirPath = resolveScanPath(treeUri)
                    val scanned = dirPath?.let { libraryRepository.scanDirectoryForAudio(it) } ?: emptyList()
                    if (scanned.isNotEmpty()) {
                        runCatching { prefs.setFilePaths(scanned) }
                        loadAndApply(scanned, ++enrichGen)
                        return@launch
                    }
                }
                _events.emit(UiEvent.OpenFilePicker)
                return@launch
            }
            applyLibrary(songs)
        }
    }

    /** Mirrors LibraryRepository.resolveFolderPath for the direct scan. */
    private fun resolveScanPath(treeUri: String): String? = runCatching {
        when {
            treeUri.startsWith("/") -> treeUri
            treeUri.startsWith("file://") -> Uri.parse(treeUri).path
            else -> {
                val uri = Uri.parse(treeUri)
                val id = if (uri.scheme == "content") {
                    runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                } else null
                if (id != null && id.startsWith("primary:")) {
                    "/storage/emulated/0/" + id.removePrefix("primary:")
                } else null
            }
        }
    }.getOrNull()

    fun loadSongs(paths: List<String>, persistPaths: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val gen = ++enrichGen
            if (persistPaths) {
                runCatching { prefs.setFilePaths(paths) }
            }
            loadAndApply(paths, gen)
        }
    }

    private suspend fun loadAndApply(paths: List<String>, gen: Int) {
        val newSongs = withContext(Dispatchers.IO) {
            libraryRepository.parseMetadataForPaths(paths)
        }
        applyLibrary(newSongs, gen)
    }

    private suspend fun applyLibrary(newSongs: List<Song>, gen: Int = ++enrichGen) {
        _state.update {
            it.copy(
                songs = newSongs,
                grouped = AlbumGrouper.groupByAlbum(newSongs),
                initialized = true,
                loading = false,
            )
        }
        rebuildSearchIndex()
        restoreCachedArt(gen)
        enrichAlbumArt(gen)
    }

    private fun rebuildSearchIndex() {
        // Mismo orden que el grid (sortedAlbumKeys) para conservar el orden.
        val st = _state.value
        val idx = mutableListOf<SearchEntry>()
        for (albumKey in st.sortedAlbumKeys) {
            val albumNorm = normSearch(albumKey)
            for (s in st.grouped[albumKey] ?: emptyList()) {
                idx.add(
                    SearchEntry(
                        song = s,
                        albumKey = albumKey,
                        title = normSearch(s.displayTitle),
                        artist = normSearch(s.artist ?: ""),
                        album = albumNorm,
                    )
                )
            }
        }
        searchIndex = idx
    }

    // ---- Album art: disk cache + enrichment ----

    private suspend fun restoreCachedArt(gen: Int) {
        val entries = _state.value.grouped.entries.toList()
        var changed = false
        for ((key, albumSongs) in entries) {
            if (gen != enrichGen || !_state.value.initialized) return
            if (albumSongs.any { it.albumArt != null }) continue
            val cached = artCache.loadCachedAlbumArt(key, Artists.albumArtistFor(albumSongs))
            if (gen != enrichGen) return
            if (cached == null) continue
            assignArt(key, cached)
            changed = true
            publishArtForCurrentIfIn(albumSongs, cached)
        }
        // changed tracks the port's final notify; state was already emitted per album.
        if (changed && gen != enrichGen) return
    }

    private suspend fun enrichAlbumArt(gen: Int) {
        val entries = _state.value.grouped.entries.toList()
        for ((key, albumSongs) in entries) {
            if (gen != enrichGen || !_state.value.initialized) return
            if (albumSongs.any { it.albumArt != null }) continue
            var bytes: ByteArray? = null
            for (s in albumSongs) {
                if (gen != enrichGen) return
                bytes = extractArt(s)
                if (bytes != null) break
            }
            if (gen != enrichGen) return
            if (bytes == null) continue
            assignArt(key, bytes)
            publishArtForCurrentIfIn(albumSongs, bytes)
            artCache.cacheAlbumArt(key, bytes, Artists.albumArtistFor(albumSongs))
        }
    }

    /** Si el tema actual es del álbum, re-publica el MediaItem y la paleta. */
    private fun publishArtForCurrentIfIn(albumSongs: List<Song>, bytes: ByteArray) {
        val curUri = PlayerBus.currentSong?.playUri ?: return
        if (albumSongs.any { it.playUri == curUri }) {
            connection.launch { refreshArt() }
            viewModelScope.launch { AuraPalette.updateForArt(bytes) }
        }
    }

    private fun assignArt(albumKey: String, bytes: ByteArray) {
        _state.update { st ->
            val albumSongs = st.grouped[albumKey] ?: return@update st
            val paths = albumSongs.map { it.path }.toSet()
            st.copy(
                songs = st.songs.map { s ->
                    if (s.path in paths && s.albumArt == null) s.copy(albumArt = bytes) else s
                },
                grouped = st.grouped.toMutableMap()
                    .apply { put(albumKey, albumSongs.map { it.copy(albumArt = bytes) }) },
            )
        }
    }

    private suspend fun extractArt(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            try {
                if (song.isContentUri) {
                    retriever.setDataSource(context, Uri.parse(song.playUri))
                } else {
                    retriever.setDataSource(song.playUri)
                }
                val data = retriever.embeddedPicture ?: return@withContext null
                if (data.isEmpty()) return@withContext null
                if (data.size <= MAX_ART_BYTES) return@withContext data
                // Downscale + JPEG 85 (port del extractArt del MainActivity antiguo).
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(
                    data, 0, data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@withContext null
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bmp.recycle()
                out.toByteArray()
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            null
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

    /**
     * Shuffle del resto con el tema actual primero (cuando pertenece a la
     * lista); segundo valor = autoPlay (el estado de reproducción actual).
     */
    private fun currentFirstShuffle(list: List<Song>): Pair<List<Song>, Boolean>? {
        val current = PlayerBus.currentSong ?: return null
        if (list.none { it.playUri == current.playUri }) return null
        val rest = list.filter { it.playUri != current.playUri }.shuffled()
        return (listOf(current) + rest) to PlayerBus.playing.value
    }

    fun playShuffled() {
        val songs = _state.value.songs
        if (songs.isEmpty()) return
        val kept = currentFirstShuffle(songs)
        if (kept != null) {
            val (list, playing) = kept
            playQueueList(list, initialIndex = 0, label = "Aleatorio Todo", autoPlay = playing, resumeIfSameSong = true, shuffled = true)
        } else {
            playQueueList(songs.shuffled(), initialIndex = 0, label = "Aleatorio Todo", autoPlay = true, shuffled = true)
        }
    }

    fun playAlbum(albumName: String, albumSongs: List<Song>, startIndex: Int = 0) {
        playQueueList(albumSongs, initialIndex = startIndex, label = albumName)
    }

    fun playAlbumShuffled(albumName: String, albumSongs: List<Song>) {
        if (albumSongs.isEmpty()) return
        val kept = currentFirstShuffle(albumSongs)
        if (kept != null) {
            val (list, playing) = kept
            playQueueList(list, initialIndex = 0, label = "$albumName (Aleatorio)", autoPlay = playing, resumeIfSameSong = true, shuffled = true)
        } else {
            playQueueList(albumSongs.shuffled(), initialIndex = 0, label = "$albumName (Aleatorio)", autoPlay = true, shuffled = true)
        }
    }

    fun playSelectedShuffle() {
        val st = _state.value
        if (st.selectedAlbums.isEmpty()) return
        val list = st.selectedAlbums.flatMap { st.grouped[it] ?: emptyList() }
        if (list.isEmpty()) return
        val kept = currentFirstShuffle(list)
        if (kept != null) {
            val (ordered, playing) = kept
            playQueueList(ordered, initialIndex = 0, label = "Aleatorio de Selección", autoPlay = playing, resumeIfSameSong = true, shuffled = true)
        } else {
            playQueueList(list.shuffled(), initialIndex = 0, label = "Aleatorio de Selección", autoPlay = true, shuffled = true)
        }
        _state.update { it.copy(selectedAlbums = emptySet(), currentTab = ViewTab.ALBUMS) }
    }

    // ---- Queue ----

    fun addToQueueNext(song: Song) {
        connection.launch { addNext(song) }
        _events.tryEmit(UiEvent.ShowSnackbar("Añadida a continuación: ${song.displayTitle}"))
    }

    fun addToQueueEnd(song: Song) {
        connection.launch { addEnd(song) }
        _events.tryEmit(UiEvent.ShowSnackbar("Añadida al final de la cola: ${song.displayTitle}"))
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
        _state.update {
            val next = it.selectedAlbums.toMutableSet()
            if (!next.add(album)) next.remove(album)
            it.copy(selectedAlbums = next)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedAlbums = emptySet()) }
    }

    fun reorderAlbums(newOrder: List<String>) {
        viewModelScope.launch {
            runCatching { prefs.saveAlbumOrder(newOrder) }
            _state.update { it.copy(albumOrder = newOrder, albumOrderChanged = true) }
        }
    }

    fun setTab(tab: ViewTab) {
        _state.update { it.copy(currentTab = tab) }
    }

    fun setReordering(v: Boolean) {
        _state.update { it.copy(reordering = v) }
    }

    /** La UI notificó el reordenado: evita un segundo aviso al salir de nuevo. */
    fun consumeAlbumOrderChanged() {
        _state.update { it.copy(albumOrderChanged = false) }
    }

    fun startSearch() {
        _state.update { it.copy(searching = true, searchQuery = "", searchResults = emptyList()) }
    }

    fun closeSearch() {
        searchDebounceJob?.cancel()
        _state.update { it.copy(searching = false, searchQuery = "", searchResults = emptyList()) }
    }

    fun updateSearchQuery(q: String) {
        _state.update { it.copy(searchQuery = q) }
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(250)
            if (!_state.value.searching) return@launch
            val results = if (q.isEmpty()) emptyList() else search(q)
            _state.update { it.copy(searchResults = results) }
        }
    }

    private fun search(query: String): List<Pair<String, Song>> {
        val q = normSearch(query)
        if (q.isEmpty()) return emptyList()
        return searchIndex
            .filter { q in it.title || q in it.artist || q in it.album }
            .map { it.albumKey to it.song }
    }

    // ---- Info / cache ----

    /** Artistas únicos normalizados (feat. no duplica; "Tyler, The Creator" es 1). */
    fun countUniqueArtists(): Int {
        val set = HashSet<String>()
        for (s in _state.value.songs) {
            for (a in Artists.parseArtists(s.artist)) {
                set.add(Artists.normalize(a))
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
    }

    /**
     * Sync manual desde el menú: verifica faltantes, descarga y emite el
     * resultado como notificación. Segunda pulsación mientras sincroniza
     * cancela (el menú solo llama aquí).
     */
    fun syncLyrics() {
        viewModelScope.launch {
            val songs = _state.value.songs
            if (songs.isEmpty()) {
                _events.emit(
                    UiEvent.ShowNotification("No hay canciones cargadas", Icons.Rounded.WarningAmber, EighthTheme.TextSecondary)
                )
                return@launch
            }
            if (_state.value.lyricsSyncing) {
                cancelLyricsSync()
                _events.emit(UiEvent.ShowNotification("Cancelando sincronización...", Icons.Rounded.Cancel))
                return@launch
            }
            _events.emit(UiEvent.ShowNotification("Verificando letras...", Icons.Rounded.Search, durationMs = 1000))

            _state.update { it.copy(lyricsSyncing = true, lyricsSyncedCount = 0, lyricsTotalToSync = songs.size) }
            lyricsCancelRequested = false
            try {
                val lrcNames = lyricsRepository.cachedLrcNames()
                var missing = 0
                for (s in songs) {
                    if (lyricsCancelRequested) break
                    if (!lyricsRepository.hasLrcIn(lrcNames, s)) missing++
                }
                if (lyricsCancelRequested) {
                    _state.update { it.copy(lyricsSyncing = false) }
                    return@launch
                }
                if (missing == 0) {
                    _state.update { it.copy(lyricsSyncing = false) }
                    _events.emit(
                        UiEvent.ShowNotification(
                            "Ya están todas las letras (${songs.size}/${songs.size})",
                            Icons.Rounded.CheckCircle,
                            GREEN,
                        )
                    )
                    return@launch
                }
                _events.emit(
                    UiEvent.ShowNotification("Faltan $missing letras, descargando...", Icons.Rounded.Download, durationMs = 2000)
                )
                val fetched = lyricsRepository.syncMissing(
                    songs,
                    onProgress = { done, total ->
                        if (done % 5 == 0 || done == total) {
                            _state.update { it.copy(lyricsSyncedCount = done, lyricsTotalToSync = total) }
                        }
                    },
                    shouldCancel = { lyricsCancelRequested },
                )
                _state.update { it.copy(lyricsSyncing = false) }
                when {
                    fetched == missing -> _events.emit(
                        UiEvent.ShowNotification(
                            "Ya están todas las letras (descargadas $fetched/$missing)",
                            Icons.Rounded.CheckCircle,
                            GREEN,
                        )
                    )
                    fetched > 0 -> _events.emit(
                        UiEvent.ShowNotification(
                            "Descargadas $fetched de $missing letras. Ya están todas las disponibles.",
                            Icons.Rounded.CheckCircle,
                            GREEN,
                        )
                    )
                    else -> _events.emit(
                        UiEvent.ShowNotification(
                            "No se encontraron $missing letras nuevas",
                            Icons.Rounded.Info,
                            EighthTheme.TextSecondary,
                        )
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                _state.update { it.copy(lyricsSyncing = false) }
            }
        }
    }

    private companion object {
        const val MAX_ART_BYTES = 300 * 1024
        val ORANGE = Color(0xFFFFB74D) // Colors.orange.shade300
        val GREEN = Color(0xFF66BB6A) // Colors.green.shade400
    }
}
