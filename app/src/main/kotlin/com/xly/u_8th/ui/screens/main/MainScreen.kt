package com.xly.u_8th.ui.screens.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import com.xly.u_8th.domain.ViewTab
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.ui.MainViewModel
import com.xly.u_8th.ui.UiEvent
import com.xly.u_8th.ui.components.AlbumDetailsSheet
import com.xly.u_8th.ui.components.AlbumGrid
import com.xly.u_8th.ui.components.AppHeader
import com.xly.u_8th.ui.components.ArtistAlbumsSheet
import com.xly.u_8th.ui.components.EmptyState
import com.xly.u_8th.ui.components.GlassNotification
import com.xly.u_8th.ui.components.InfoDialog
import com.xly.u_8th.ui.components.MiniPlayer
import com.xly.u_8th.ui.components.NotificationState
import com.xly.u_8th.ui.components.QueueTab
import com.xly.u_8th.ui.components.SearchBarWidget
import com.xly.u_8th.ui.components.SongTile
import com.xly.u_8th.ui.components.TabSelector
import com.xly.u_8th.ui.nav.AppScreen
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla principal (port of screens/music_player_screen.dart): header,
 * pestañas Álbumes/Cola con pager, búsqueda, sheets y mini-player.
 */
@Composable
fun MainScreen(nav: NavController) {
    val vm: MainViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val application = context.applicationContext as EighthApplication
    val connection = application.playerConnection
    val libraryRepository = application.libraryRepository
    val density = LocalDensity.current
    val aura = animatedAura()
    val scope = rememberCoroutineScope()

    val notificationState = remember { NotificationState() }
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- PlayerBus ----
    val queue by PlayerBus.queue.collectAsState()
    val queueIndex by PlayerBus.queueIndex.collectAsState()
    val queueLabel by PlayerBus.queueLabel.collectAsState()
    val playing by PlayerBus.playing.collectAsState()
    val positionMs by PlayerBus.positionMs.collectAsState()
    val durationMs by PlayerBus.durationMs.collectAsState()
    val shuffleEnabled by PlayerBus.shuffleEnabled.collectAsState()

    // ---- Search field (fuente de verdad del texto; resultados con debounce en el VM) ----
    var searchText by remember { mutableStateOf("") }

    // ---- Overlays ----
    var albumSheet by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var artistSheet by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    // ---- Pickers ----
    var pendingFolderPick by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            vm.pickFolder(uri.toString())
        }
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
        }
        vm.loadFiles(uris.map { it.toString() })
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // El listado maneja el resultado vacío con el fallback al picker.
        if (pendingFolderPick) {
            pendingFolderPick = false
            folderPicker.launch(null)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun requestFolderPick() {
        if (libraryRepository.hasAudioPermission()) {
            folderPicker.launch(null)
        } else {
            pendingFolderPick = true
            audioPermissionLauncher.launch(libraryRepository.audioPermissionString())
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- One-shot events del VM ----
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.ShowNotification ->
                    notificationState.show(event.text, event.icon, event.tint, event.durationMs)
                is UiEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(event.text, duration = SnackbarDuration.Short)
                UiEvent.OpenFilePicker -> filesPicker.launch(arrayOf("audio/*"))
            }
        }
    }

    fun openFullPlayer() {
        if (queue.isNotEmpty()) nav.push(AppScreen.FullPlayer)
    }

    // El sheet llama con 200ms de retardo (animación de bajada).
    fun playAlbumDelayed(album: String, songs: List<Song>, index: Int) {
        scope.launch {
            delay(200)
            vm.playAlbum(album, songs, index)
        }
    }

    fun playAlbumShuffledDelayed(album: String, songs: List<Song>) {
        scope.launch {
            delay(200)
            vm.playAlbumShuffled(album, songs)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppHeader(
                isSearching = state.searching,
                hasSongs = state.songs.isNotEmpty(),
                currentTab = state.currentTab,
                isReorderingAlbums = state.reordering,
                selectedAlbumCount = state.selectedAlbums.size,
                isSyncing = state.lyricsSyncing,
                syncDone = state.lyricsSyncedCount,
                syncTotal = state.lyricsTotalToSync,
                onSearchToggle = if (state.searching) {
                    {
                        searchText = ""
                        vm.closeSearch()
                    }
                } else {
                    {
                        searchText = ""
                        vm.startSearch()
                    }
                },
                onShuffle = { vm.playShuffled() },
                onPlaySelected = { vm.playSelectedShuffle() },
                onPickFolder = { requestFolderPick() },
                onShowInfo = { showInfo = true },
                onToggleReorder = {
                    val wasReordering = state.reordering
                    vm.setReordering(!wasReordering)
                    if (wasReordering && state.albumOrderChanged) {
                        vm.consumeAlbumOrderChanged()
                        notificationState.show(
                            "Se han reordenado los álbumes",
                            Icons.Rounded.CheckCircle,
                            Color(0xFF66BB6A),
                        )
                    }
                },
                onSyncLyrics = { vm.syncLyrics() },
            )
            if (state.searching) {
                SearchBarWidget(
                    query = searchText,
                    onQueryChanged = { q ->
                        searchText = q
                        vm.updateSearchQuery(q)
                    },
                    onClear = {
                        searchText = ""
                        vm.updateSearchQuery("")
                    },
                    onClose = {
                        searchText = ""
                        vm.closeSearch()
                    },
                )
            } else {
                TabSelector(
                    currentTab = state.currentTab,
                    albumCount = state.albumCount,
                    queueLength = queue.size,
                    onTabSelected = { tab -> vm.setTab(tab) },
                )
            }
            Box(Modifier.weight(1f)) {
                when {
                    !state.initialized || state.loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = aura, strokeWidth = 2.5.dp)
                        }
                    }
                    state.songs.isEmpty() -> EmptyState(onPickFolder = { requestFolderPick() })
                    state.searching -> SearchResultsView(
                        query = searchText.trim(),
                        results = state.searchResults,
                        currentSongUri = PlayerBus.currentSong?.playUri,
                        onPlay = { albumKey, song ->
                            val albumSongs = state.grouped[albumKey] ?: return@SearchResultsView
                            val idx = albumSongs.indexOf(song).coerceAtLeast(0)
                            vm.playAlbum(albumKey, albumSongs, idx)
                        },
                        onAddToNext = { vm.addToQueueNext(it) },
                        onAddToEnd = { vm.addToQueueEnd(it) },
                    )
                    else -> {
                        val pagerState = rememberPagerState(
                            initialPage = if (state.currentTab == ViewTab.QUEUE) 1 else 0,
                            pageCount = { 2 },
                        )
                        // Sincroniza la página si el tab cambió por otra vía
                        // (playSelectedShuffle fuerza Álbumes desde la Cola).
                        LaunchedEffect(state.currentTab) {
                            val target = if (state.currentTab == ViewTab.ALBUMS) 0 else 1
                            if (pagerState.currentPage != target) {
                                pagerState.animateScrollToPage(
                                    target,
                                    animationSpec = tween(280, easing = EighthTheme.EasingOutCubic),
                                )
                            }
                        }
                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }
                                .collect { page -> vm.setTab(if (page == 0) ViewTab.ALBUMS else ViewTab.QUEUE) }
                        }
                        HorizontalPager(
                            state = pagerState,
                            // Mantiene la cola viva (scroll/estado) entre pestañas.
                            beyondViewportPageCount = 1,
                            userScrollEnabled = !state.reordering,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            if (page == 0) {
                                AlbumGrid(
                                    grouped = state.grouped,
                                    sortedAlbumKeys = state.sortedAlbumKeys,
                                    selectedAlbums = state.selectedAlbums,
                                    isReordering = state.reordering,
                                    onToggleAlbum = { vm.toggleAlbum(it) },
                                    onOpenDetails = { name, songs -> albumSheet = name to songs },
                                    onReorder = { vm.reorderAlbums(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                QueueTab(
                                    queue = queue,
                                    queueIndex = queueIndex,
                                    queueLabel = queueLabel,
                                    isShuffled = shuffleEnabled,
                                    onToggleShuffle = { vm.toggleQueueShuffle(!shuffleEnabled) },
                                    onReorder = { o, n -> vm.reorderQueue(o, n) },
                                    onRemove = { vm.removeFromQueue(it) },
                                    onTapRow = { vm.seekToIndex(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
            // Mini player: aparece/desaparece con la cola.
            AnimatedVisibility(
                visible = queue.isNotEmpty(),
                enter = fadeIn(tween(320, easing = EighthTheme.EasingStandard)) +
                    slideInVertically(tween(320, easing = EighthTheme.EasingStandard)) { it },
                exit = fadeOut(tween(320)) +
                    slideOutVertically(tween(320)) { it },
            ) {
                val song = PlayerBus.currentSong
                if (song != null) {
                    val nextSong =
                        if (queueIndex + 1 < queue.size) queue[queueIndex + 1] else queue.first()
                    val prevSong =
                        if (queueIndex - 1 >= 0) queue[queueIndex - 1] else queue.last()
                    MiniPlayer(
                        song = song,
                        nextSong = nextSong,
                        previousSong = prevSong,
                        playing = playing,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onTap = { openFullPlayer() },
                        onPrevious = { connection.launch { prev() } },
                        onNext = { connection.launch { next() } },
                        onTogglePlay = { connection.launch { togglePlay() } },
                        onSeek = { ms -> connection.launch { seekTo(ms) } },
                    )
                }
            }
        }

        // ---- Overlays ----
        albumSheet?.let { (albumName, songs) ->
            AlbumDetailsSheet(
                albumName = albumName,
                albumSongs = songs,
                currentSongPath = PlayerBus.currentSong?.playUri,
                artistName = Artists.albumArtistFor(songs),
                onArtistTap = { artist ->
                    albumSheet = null
                    artistSheet = artist
                },
                onPlayAlbum = { name, albumSongs, idx -> playAlbumDelayed(name, albumSongs, idx) },
                onPlayAlbumShuffled = { name, albumSongs -> playAlbumShuffledDelayed(name, albumSongs) },
                onAddToNext = { vm.addToQueueNext(it) },
                onAddToEnd = { vm.addToQueueEnd(it) },
                onDismiss = { albumSheet = null },
            )
        }
        artistSheet?.let { artist ->
            // El álbum sale en el menú del artista principal de su PRIMER tema.
            val wanted = Artists.parseArtists(artist)
            val displayArtist = wanted.firstOrNull() ?: artist.trim()
            val albums = state.grouped.entries
                .filter { Artists.albumMatchesArtist(it.value, wanted) }
                .map { it.key to it.value }
            ArtistAlbumsSheet(
                artist = displayArtist,
                albums = albums,
                onOpenAlbum = { name, songs ->
                    artistSheet = null
                    albumSheet = name to songs
                },
                onDismiss = { artistSheet = null },
            )
        }
        if (showInfo) {
            InfoDialog(
                songCount = state.songs.size,
                albumCount = state.albumCount,
                artistCount = vm.countUniqueArtists(),
                onClearCache = { onResult -> vm.clearArtCache(onResult) },
                onShowNotification = { n ->
                    notificationState.show(
                        if (n == 0) "Caché ya estaba limpia" else "Caché limpiada ($n portadas)",
                        Icons.Rounded.CleaningServices,
                    )
                },
                onDismiss = { showInfo = false },
            )
        }

        val navBarPx = WindowInsets.navigationBars.getBottom(density)
        GlassNotification(
            state = notificationState,
            bottomPadding = 88.dp + with(density) { (navBarPx * 0.2f).toDp() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp),
        ) { data ->
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .border(
                        androidx.compose.foundation.BorderStroke(0.7.dp, EighthTheme.GlassBorder),
                        RoundedCornerShape(12.dp),
                    )
                    .background(
                        EighthTheme.GlassSurface.copy(alpha = 0.92f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(data.visuals.message, fontSize = 13.sp, color = EighthTheme.TextPrimary)
            }
        }
    }
}

@Composable
private fun SearchResultsView(
    query: String,
    results: List<Pair<String, Song>>,
    currentSongUri: String?,
    onPlay: (String, Song) -> Unit,
    onAddToNext: (Song) -> Unit,
    onAddToEnd: (Song) -> Unit,
) {
    // El texto del campo es la única fuente de verdad: sin texto, prompt.
    if (query.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Escribe para buscar en tu biblioteca",
                color = Color.White.copy(alpha = 0.54f),
                fontSize = 15.sp,
            )
        }
        return
    }
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Sin resultados para \"$query\"",
                color = Color.White.copy(alpha = 0.54f),
                fontSize = 15.sp,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(results.size) { index ->
            val (albumKey, song) = results[index]
            SongTile(
                song = song,
                isCurrent = currentSongUri != null && currentSongUri == song.playUri,
                onTap = { onPlay(albumKey, song) },
                onAddToNext = { onAddToNext(song) },
                onAddToEnd = { onAddToEnd(song) },
            )
        }
    }
}
