package com.xly.u_8th.ui.screens.main

import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.net.toUri
import com.xly.u_8th.ui.components.rememberWithCatalogArt
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.xly.u_8th.ui.ArtistHit
import com.xly.u_8th.ui.LibrarySearch
import com.xly.u_8th.ui.LyricHit
import com.xly.u_8th.ui.SearchResults
import com.xly.u_8th.ui.components.TintedIconBadge
import com.xly.u_8th.ui.components.glassCard
import com.xly.u_8th.ui.components.pressClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.Dp
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Playlist
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
import com.xly.u_8th.ui.components.glassPopup
import com.xly.u_8th.ui.components.InfoDialog
import com.xly.u_8th.ui.components.MiniPlayer
import com.xly.u_8th.ui.components.NotificationState
import com.xly.u_8th.ui.components.AddToPlaylistDialog
import com.xly.u_8th.ui.components.DeletePlaylistDialog
import com.xly.u_8th.ui.components.PlaylistDetailsSheet
import com.xly.u_8th.ui.components.PlaylistNameDialog
import com.xly.u_8th.ui.components.PlaylistsTab
import com.xly.u_8th.ui.components.QueueTab
import com.xly.u_8th.ui.components.resolve
import com.xly.u_8th.ui.components.SHEET_PLAY_DELAY_MS
import com.xly.u_8th.ui.components.SearchBarWidget
import com.xly.u_8th.ui.components.SongTile
import com.xly.u_8th.ui.components.TabSelector
import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.ui.components.LibraryBackdrop
import com.xly.u_8th.ui.nav.AppScreen
import com.xly.u_8th.ui.nav.LocalPlayerExpansion
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/** Espera máxima a que el teclado se oculte antes de abrir el sheet de artista. */
private const val IME_HIDE_TIMEOUT_MS = 350L

/**
 * Pantalla principal: header,
 * pestañas Álbumes/Cola con pager, búsqueda, sheets y mini-player.
 */
@Composable
fun MainScreen(nav: NavController) {
    val vm: MainViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val application = EighthApplication.get(context)
    val connection = application.playerConnection
    val libraryRepository = application.libraryRepository
    val aura = animatedAura()
    val scope = rememberCoroutineScope()

    // El scope de la composición: al salir de MainScreen los delays de
    // auto-ocultado se cancelan en vez de retener el estado.
    val notificationState = remember(scope) { NotificationState(scope) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- PlayerBus ----
    val queue by PlayerBus.queue.collectAsStateWithLifecycle()
    val queueIndex by PlayerBus.queueIndex.collectAsStateWithLifecycle()
    val queueLabel by PlayerBus.queueLabel.collectAsStateWithLifecycle()
    val playing by PlayerBus.playing.collectAsStateWithLifecycle()
    val shuffleEnabled by PlayerBus.shuffleEnabled.collectAsStateWithLifecycle()

    // Lectura derivada con invalidación real: `PlayerBus.currentSong`
    // lee .value directamente y NO registra dependencia de snapshot, así
    // que el resaltado de "sonando" solo se refrescaba por casualidad
    // (cuando otro estado emitido lo obligaba).
    val currentSongUri by remember {
        derivedStateOf { PlayerBus.queue.value.getOrNull(PlayerBus.queueIndex.value)?.playUri }
    }

    // Álbum (clave del grid) de la canción en curso, para resaltarlo.
    // Por ruta (igual que Song.equals): la cola restaurada puede traer otra
    // forma de URI que la biblioteca para la misma canción.
    val currentPath = queue.getOrNull(queueIndex)?.path
    val playingAlbumKey = remember(currentPath, state.grouped) {
        currentPath?.let { path -> state.grouped.entries.firstOrNull { e -> e.value.any { it.path == path } }?.key }
    }
    val currentArt = queue.getOrNull(queueIndex)?.rememberWithCatalogArt()?.albumArt
    // Hueco bajo el contenido: con cola activa reserva el alto del mini
    // player flotante para que la última fila siempre quede accesible.
    val contentBottomPadding = if (queue.isNotEmpty()) 104.dp else 12.dp

    // ---- Search field (fuente de verdad del texto; resultados con debounce en el VM) ----
    val searchText = state.searchQuery
    val contentStateHolder = rememberSaveableStateHolder()

    // ---- Overlays ----
    var albumSheet by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var artistSheet by remember { mutableStateOf<String?>(null) }
    // Artista tocado en la búsqueda con el teclado abierto: el sheet espera
    // a que el IME termine de bajar (si suben a la vez, la animación se
    // descompone). Tope de espera para no quedarse colgado.
    var pendingArtist by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(pendingArtist) {
        val artist = pendingArtist ?: return@LaunchedEffect
        withTimeoutOrNull(IME_HIDE_TIMEOUT_MS) {
            snapshotFlow { imeInsets.getBottom(density) > 0 }.first { visible -> !visible }
        }
        artistSheet = artist
        pendingArtist = null
    }
    var showInfo by remember { mutableStateOf(false) }
    // Playlists: ficha abierta (por id, para ver los cambios en vivo),
    // canciones pendientes de añadir y diálogos de nombre/borrado.
    var playlistSheetId by remember { mutableStateOf<String?>(null) }
    var addToPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    // Canciones de la biblioteca por ruta (con portadas) para resolver playlists.
    val songsByPath = remember(state.grouped, state.artVersion) {
        HashMap<String, Song>(state.songs.size * 2).apply {
            for (songs in state.grouped.values) for (song in songs) put(song.path, song)
        }
    }

    // ---- Pickers ----
    var pendingFolderPick by remember { mutableStateOf(false) }

    // Sube al elegir carpeta: el diálogo de descarga revisa el permiso.
    var folderGrant by remember { mutableIntStateOf(0) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Lectura y escritura: el descargador guarda en esta carpeta.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
            }
            folderGrant++
            vm.pickFolder(uri.toString())
        }
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
    // El collector NUNCA se suspende: showSnackbar bloquea hasta que el
    // snackbar se descarta (~4s) y serializaba la cola de eventos, así que
    // una notificación podía mostrarse segundos tarde. Además MainScreen
    // sale de composición al navegar al reproductor completo, por lo que el
    // VM los reproduce con replay=1 al volver.
    LaunchedEffect(vm, scope) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.ShowNotification ->
                    notificationState.show(event.text, event.icon, event.tint, event.durationMs)
                is UiEvent.ShowSnackbar ->
                    scope.launch {
                        snackbarHostState.showSnackbar(event.text, duration = SnackbarDuration.Short)
                    }
            }
        }
    }

    fun openFullPlayer() {
        if (queue.isNotEmpty()) nav.push(AppScreen.FullPlayer)
    }

    // El sheet llama con retardo (SHEET_PLAY_DELAY_MS) para que su animación
    // de bajada termine antes de empezar a reproducir.
    fun playAlbumDelayed(album: String, songs: List<Song>, index: Int) {
        scope.launch {
            delay(SHEET_PLAY_DELAY_MS)
            vm.playAlbum(album, songs, index)
        }
    }

    fun playAlbumShuffledDelayed(album: String, songs: List<Song>) {
        scope.launch {
            delay(SHEET_PLAY_DELAY_MS)
            vm.playAlbumShuffled(album, songs)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LibraryBackdrop(art = currentArt, aura = aura)
        // Barras del sistema + notch (sin IME): la cabecera arranca bajo la
        // barra de estado y el mini-player sobre la de navegación; el fondo
        // queda full-bleed. FullPlayer/Lyrics gestionan sus propios insets.
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        ) {
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
                        vm.closeSearch()
                    }
                } else {
                    {
                        vm.startSearch()
                    }
                },
                onShuffle = { vm.playShuffled() },
                onPlaySelected = { vm.playSelectedShuffle() },
                onPickFolder = { requestFolderPick() },
                onShowInfo = { showInfo = true },
                gridColumns = state.gridColumns,
                onSetGridColumns = { vm.setGridColumns(it) },
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
            // Fuera del pager para que el selector lea su posición en vivo.
            val pagerState = rememberPagerState(
                initialPage = state.currentTab.ordinal,
                pageCount = { ViewTab.entries.size },
            )
            if (state.searching) {
                SearchBarWidget(
                    query = searchText,
                    onQueryChanged = { q ->
                        vm.updateSearchQuery(q)
                    },
                    onClear = {
                        vm.updateSearchQuery("")
                    },
                    onClose = {
                        vm.closeSearch()
                    },
                )
            } else {
                TabSelector(
                    pagerPosition = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                    onTabSelected = { tab -> vm.setTab(tab) },
                )
            }
            Box(Modifier.weight(1f)) {
                contentStateHolder.SaveableStateProvider(if (state.searching) "search" else "library") {
                when {
                    // Cargando: fondo vacío, sin indicador (dura un instante).
                    !state.initialized || state.loading -> Box(Modifier.fillMaxSize())
                    state.songs.isEmpty() -> EmptyState(onPickFolder = { requestFolderPick() })
                    state.searching -> SearchResultsView(
                        query = searchText.trim(),
                        bottomContentPadding = contentBottomPadding,
                        results = state.searchResults,
                        currentSongUri = currentSongUri,
                        onOpenArtist = { artist ->
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            pendingArtist = artist
                        },
                        onPlay = { albumKey, song ->
                            val albumSongs = state.grouped[albumKey] ?: return@SearchResultsView
                            val idx = albumSongs.indexOf(song).coerceAtLeast(0)
                            vm.playAlbum(albumKey, albumSongs, idx)
                        },
                        onAddToNext = { vm.addToQueueNext(it) },
                        onAddToEnd = { vm.addToQueueEnd(it) },
                        onAddToPlaylist = { addToPlaylistSongs = listOf(it) },
                    )
                    else -> {
                        // Sincroniza la página si el tab cambió por otra vía
                        // (playSelectedShuffle fuerza Álbumes desde la Cola).
                        LaunchedEffect(state.currentTab) {
                            val target = state.currentTab.ordinal
                            if (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f) {
                                pagerState.animateScrollToPage(
                                    target,
                                    animationSpec = tween(280, easing = EighthTheme.EasingOutCubic),
                                )
                            }
                        }
                        LaunchedEffect(pagerState) {
                            // Página ASENTADA, no currentPage: al saltar de Listas a
                            // Cola el pager pasa por Álbumes y currentPage reescribía
                            // la pestaña a mitad de animación, dejándola a medias.
                            snapshotFlow { pagerState.settledPage }
                                .collect { page -> vm.setTab(ViewTab.entries[page]) }
                        }
                        HorizontalPager(
                            state = pagerState,
                            // Mantiene las pestañas vivas (scroll/estado) al cambiar.
                            beyondViewportPageCount = 2,
                            // Cambia de pestaña con un deslizamiento corto (por
                            // defecto pedía media pantalla si el gesto era lento).
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                snapPositionalThreshold = 0.18f,
                            ),
                            userScrollEnabled = !state.reordering,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            if (ViewTab.entries[page] == ViewTab.PLAYLISTS) {
                                PlaylistsTab(
                                    playlists = state.playlists,
                                    songsByPath = songsByPath,
                                    onCreate = { creatingPlaylist = true },
                                    onOpen = { playlistSheetId = it.id },
                                    bottomContentPadding = contentBottomPadding,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (ViewTab.entries[page] == ViewTab.ALBUMS) {
                                AlbumGrid(
                                    grouped = state.grouped,
                                    sortedAlbumKeys = state.sortedAlbumKeys,
                                    selectedAlbums = state.selectedAlbums,
                                    isReordering = state.reordering,
                                    onToggleAlbum = { vm.toggleAlbum(it) },
                                    onOpenDetails = { name, songs -> albumSheet = name to songs },
                                    onReorder = { vm.reorderAlbums(it) },
                                    columnsSetting = state.gridColumns,
                                    artVersion = state.artVersion,
                                    coversReady = state.coversReady,
                                    bottomContentPadding = contentBottomPadding,
                                    playingAlbumKey = playingAlbumKey,
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
                                    bottomContentPadding = contentBottomPadding,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                }
            }
        }

        // Mini player FLOTANTE: tarjeta suelta sobre el contenido (los
        // álbumes pasan por detrás), centrada abajo y sobre la barra de
        // navegación. Ya no ocupa hueco en el layout de la Column.
        AnimatedVisibility(
            visible = queue.isNotEmpty(),
            enter = fadeIn(tween(320, easing = EighthTheme.EasingStandard)) +
                slideInVertically(tween(320, easing = EighthTheme.EasingStandard)) { it },
            exit = fadeOut(tween(320)) +
                slideOutVertically(tween(320)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            val positionMs by PlayerBus.positionMs.collectAsStateWithLifecycle()
            val durationMs by PlayerBus.durationMs.collectAsStateWithLifecycle()
            val loopMode by PlayerBus.loopMode.collectAsStateWithLifecycle()
            val expansion = LocalPlayerExpansion.current
            val song = queue.getOrNull(queueIndex)?.rememberWithCatalogArt()
            if (song != null) {
                // Vecinos reales para el carrusel: solo se da la vuelta a la
                // cola con "repetir todo" (seekToNext no salta si no).
                val loopAll = loopMode == LoopMode.ALL && queue.size > 1
                val nextSong = (queue.getOrNull(queueIndex + 1) ?: queue.first().takeIf { loopAll })
                    ?.rememberWithCatalogArt()
                val prevSong = (queue.getOrNull(queueIndex - 1) ?: queue.last().takeIf { loopAll })
                    ?.rememberWithCatalogArt()
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
                    onExpandDrag = expansion::dragFromCollapsed,
                    onExpandRelease = { vy -> expansion.release(vy, nav) },
                    onExpandCancel = { expansion.cancel(nav) },
                    expandedFraction = { expansion.fraction.value },
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp),
                    onCardBounds = { expansion.miniBounds = it },
                )
            }
        }

        // ---- Overlays ----
        albumSheet?.let { (albumName, songs) ->
            AlbumDetailsSheet(
                albumName = albumName,
                albumSongs = songs,
                currentSongPath = currentSongUri,
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
                onAddToPlaylist = { addToPlaylistSongs = it },
            )
        }
        playlistSheetId?.let { id ->
            val playlist = state.playlists.firstOrNull { it.id == id }
            if (playlist == null) {
                // Borrada mientras estaba abierta: se cierra la ficha.
                LaunchedEffect(id) { playlistSheetId = null }
            } else {
                val songs = remember(playlist, songsByPath) { playlist.resolve(songsByPath) }
                PlaylistDetailsSheet(
                    playlist = playlist,
                    songs = songs,
                    currentSongPath = currentSongUri,
                    onPlay = { idx ->
                        scope.launch {
                            delay(SHEET_PLAY_DELAY_MS)
                            vm.playPlaylist(playlist, songs, idx)
                        }
                    },
                    onPlayShuffled = {
                        scope.launch {
                            delay(SHEET_PLAY_DELAY_MS)
                            vm.playPlaylistShuffled(playlist, songs)
                        }
                    },
                    onAddToNext = { vm.addToQueueNext(it) },
                    onAddToEnd = { vm.addToQueueEnd(it) },
                    onRemove = { vm.removeFromPlaylist(playlist.id, it.path) },
                    onRename = { renamingPlaylist = playlist },
                    onDelete = { deletingPlaylist = playlist },
                    onDismiss = { playlistSheetId = null },
                )
            }
        }
        addToPlaylistSongs?.let { songs ->
            AddToPlaylistDialog(
                playlists = state.playlists,
                count = songs.size,
                onPick = { vm.addToPlaylist(it.id, songs) },
                onCreate = { name -> vm.createPlaylist(name, songs) },
                onDismiss = { addToPlaylistSongs = null },
            )
        }
        if (creatingPlaylist) {
            PlaylistNameDialog(
                title = "Nueva playlist",
                initialName = "",
                confirmLabel = "Crear",
                onConfirm = { vm.createPlaylist(it) },
                onDismiss = { creatingPlaylist = false },
            )
        }
        renamingPlaylist?.let { playlist ->
            PlaylistNameDialog(
                title = "Renombrar playlist",
                initialName = playlist.name,
                confirmLabel = "Guardar",
                onConfirm = { vm.renamePlaylist(playlist.id, it) },
                onDismiss = { renamingPlaylist = null },
            )
        }
        deletingPlaylist?.let { playlist ->
            DeletePlaylistDialog(
                name = playlist.name,
                onConfirm = {
                    playlistSheetId = null
                    vm.deletePlaylist(playlist.id)
                },
                onDismiss = { deletingPlaylist = null },
            )
        }
        artistSheet?.let { artist ->
            // El álbum sale en el menú de quien firma la mayoría de sus
            // temas (ver Artists.albumArtistKeys). Cacheado: parsear (regex) el artista de cada álbum en
            // cada recomposición era caro mientras el panel está abierto.
            val displayArtist = remember(artist) { Artists.displayName(artist) }
            val albums = remember(artist, state.grouped) { Artists.albumsOf(artist, state.grouped) }
            val looseSongs = remember(artist, state.grouped) { Artists.looseSongsOf(artist, state.grouped) }
            ArtistAlbumsSheet(
                artist = displayArtist,
                albums = albums,
                looseSongs = looseSongs,
                onOpenAlbum = { name, songs ->
                    artistSheet = null
                    albumSheet = name to songs
                },
                onPlayArtist = { name, songs -> playAlbumDelayed(name, songs, 0) },
                onPlayArtistShuffled = { name, songs -> playAlbumShuffledDelayed(name, songs) },
                onDismiss = { artistSheet = null },
            )
        }
        if (showInfo) {
            // `remember(songs)`: countUniqueArtists() recorre la biblioteca
            // entera con dos regex por canción. Invocado en el cuerpo de la
            // composición, se repetía en cada cambio de estado del player.
            val artistCount = remember(state.songs) { vm.countUniqueArtists() }
            InfoDialog(
                songCount = state.songs.size,
                albumCount = state.albumCount,
                artistCount = artistCount,
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

        // 96dp: justo por encima del mini player flotante (≈84dp de alto).
        GlassNotification(
            state = notificationState,
            bottomPadding = 104.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        ) { data ->
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .glassPopup(RoundedCornerShape(EighthTheme.RadiusL))
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
    results: SearchResults,
    bottomContentPadding: Dp,
    currentSongUri: String?,
    onOpenArtist: (String) -> Unit,
    onPlay: (String, Song) -> Unit,
    onAddToNext: (Song) -> Unit,
    onAddToEnd: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
) {
    // El texto del campo es la única fuente de verdad: sin texto, prompt.
    if (query.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Escribe para buscar en tu biblioteca",
                color = EighthTheme.TextSecondary,
                fontSize = 15.sp,
            )
        }
        return
    }
    if (results.isEmpty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Sin resultados para \"$query\"",
                color = EighthTheme.TextSecondary,
                fontSize = 15.sp,
            )
        }
        return
    }
    val songs = results.songs
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomContentPadding,
        ),
    ) {
        if (results.artists.isNotEmpty()) {
            item(key = "h:artists") { SearchSectionHeader("Artistas") }
            items(
                count = results.artists.size,
                key = { index -> "a:" + results.artists[index].key },
            ) { index ->
                val hit = results.artists[index]
                ArtistResultRow(hit, onClick = { onOpenArtist(hit.name) })
            }
            if (songs.isNotEmpty()) item(key = "h:songs") { SearchSectionHeader("Canciones") }
        }
        // `key` por canción: sin él el estado interno de SongTile (menú desplegable,
        // decodificación de portada) se reasigna por posición y el menú
        // abierto salta a otra canción al cambiar la lista de resultados.
        items(
            count = songs.size,
            key = { index -> songs[index].second.stableId },
        ) { index ->
            val (albumKey, song) = songs[index]
            SongTile(
                song = song,
                isCurrent = currentSongUri != null && currentSongUri == song.playUri,
                onTap = { onPlay(albumKey, song) },
                onAddToNext = { onAddToNext(song) },
                onAddToEnd = { onAddToEnd(song) },
                onAddToPlaylist = { onAddToPlaylist(song) },
            )
        }
        if (results.lyrics.isNotEmpty()) {
            item(key = "h:lyrics") { SearchSectionHeader("En letras") }
            items(
                count = results.lyrics.size,
                key = { index -> "l:" + results.lyrics[index].song.stableId },
            ) { index ->
                val hit = results.lyrics[index]
                LyricResultRow(
                    hit = hit,
                    isCurrent = currentSongUri != null && currentSongUri == hit.song.playUri,
                    onClick = { onPlay(hit.albumKey, hit.song) },
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = EighthTheme.TextTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun ArtistResultRow(hit: ArtistHit, onClick: () -> Unit) {
    val aura = animatedAura()
    Row(
        Modifier
            .padding(bottom = 7.dp)
            .fillMaxWidth()
            .pressClickable(pressedScale = 0.97f, onClick = onClick)
            .glassCard()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintedIconBadge(Icons.Rounded.Person, aura)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hit.name,
                color = EighthTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val counts = LibrarySearch.artistCountLabel(hit.albumCount, hit.looseSongCount)
            if (counts.isNotEmpty()) {
                Text(
                    counts,
                    color = EighthTheme.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = EighthTheme.TextTertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun LyricResultRow(hit: LyricHit, isCurrent: Boolean, onClick: () -> Unit) {
    val aura = animatedAura()
    Row(
        Modifier
            .padding(bottom = 7.dp)
            .fillMaxWidth()
            .pressClickable(pressedScale = 0.97f, onClick = onClick)
            .glassCard(active = isCurrent, accent = EighthTheme.accent(aura))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintedIconBadge(Icons.Rounded.Lyrics, aura)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(hit.song.displayTitle)
                    hit.song.artist?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                color = EighthTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "«${hit.line}»",
                color = EighthTheme.TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
