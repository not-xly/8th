package com.xly.u_8th.ui.screens.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.components.GlassDropdownMenu
import com.xly.u_8th.ui.components.GlassMenuItem
import com.xly.u_8th.ui.components.GlassPill
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.data.LrcLine
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.screens.fullplayer.rememberPlayerConnection
import com.xly.u_8th.ui.theme.EighthTheme

/**
 * Lyrics pipeline, local .lrc ->
 * one-shot network fetch -> synced/plain/missing rendering with line
 * highlighting and auto-scroll keeping the current line at ~40% height.
 */
@Composable
fun LyricsView(song: Song?, modifier: Modifier = Modifier) {
    if (song == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No hay canción", color = EighthTheme.TextSecondary)
        }
        return
    }
    key(song.playUri) { SongLyrics(song, modifier) }
}

private sealed interface LyricsAction {
    data object Load : LyricsAction
    data object Retry : LyricsAction
    data class Import(val uri: android.net.Uri) : LyricsAction
    data class Save(val text: String) : LyricsAction
    data class Offset(val value: Long) : LyricsAction
}

@Composable
private fun SongLyrics(song: Song, modifier: Modifier) {
    val context = LocalContext.current
    val app = remember(context) { EighthApplication.get(context) }
    val repository = app.lyricsRepository
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connection = rememberPlayerConnection()
    var state by remember { mutableStateOf<LyricsUiState>(LyricsUiState.Loading) }
    var action by remember { mutableStateOf<LyricsAction?>(LyricsAction.Load) }
    var offsetMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var returnToCurrent by remember { mutableIntStateOf(0) }
    var pendingImportSong by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = action != null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val expected = pendingImportSong
        pendingImportSong = null
        if (uri != null) {
            if (expected != song.playUri) error = "La canción cambió; selecciona el archivo de nuevo"
            else action = LyricsAction.Import(uri)
        }
    }

    LaunchedEffect(action, lifecycle) {
        val request = action ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                when (request) {
                    is LyricsAction.Import -> {
                        val text = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(request.uri)?.use { readLrcImport(it) }
                                ?: throw IllegalArgumentException("No se pudo abrir el archivo")
                        }
                        // Validación + parseo LRC (regex por línea) fuera del
                        // hilo principal: un .lrc de 256 KiB lo bloqueaba.
                        withContext(Dispatchers.Default) { repository.importLrc(song, text) }
                    }
                    is LyricsAction.Save -> withContext(Dispatchers.Default) {
                        repository.importLrc(song, validateLrcImport(request.text))
                    }
                    is LyricsAction.Offset -> withContext(Dispatchers.IO) { repository.setOffsetMs(song, request.value) }
                    LyricsAction.Retry -> {
                        if (!repository.fetchAndSave(song, force = true, interactive = true)) {
                            val plain = (state as? LyricsUiState.Loaded)?.synced == false
                            throw IllegalStateException(
                                if (plain) "No se encontró una versión sincronizada"
                                else "No se encontró una letra; importa o edita otra versión",
                            )
                        }
                    }
                    LyricsAction.Load -> Unit
                }
                val savedOffset = withContext(Dispatchers.IO) { repository.getOffsetMs(song) }
                var lines = repository.loadLrc(song)
                if (request == LyricsAction.Load && lines.isNullOrEmpty()) {
                    if (repository.fetchAndSave(song, interactive = true)) lines = repository.loadLrc(song)
                }
                ensureActive()
                offsetMs = savedOffset.coerceIn(-60_000L, 60_000L)
                state = if (lines.isNullOrEmpty()) LyricsUiState.Missing
                    else LyricsUiState.Loaded(lines, lines.any { it.timestampMs > 0 })
                error = null
                if (request is LyricsAction.Save) editing = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudieron actualizar las letras"
                if (state == LyricsUiState.Loading) state = LyricsUiState.Missing
            }
            action = null
            awaitCancellation()
        }
    }

    val loadedState = state as? LyricsUiState.Loaded
    val synced = loadedState?.synced == true

    // Traducción opcional: solo letras en inglés; ante cualquier fallo (sin
    // red para el modelo, ML Kit no disponible) se queda el original.
    var showOffset by remember { mutableStateOf(false) }

    fun openImport() {
        pendingImportSong = song.playUri
        try { picker.launch(arrayOf("*/*")) } catch (_: Exception) {
            pendingImportSong = null
            error = "No se pudo abrir el selector de documentos"
        }
    }

    Column(modifier) {
        // Barra de herramientas: estado de la letra a la izquierda,
        // sincronización y menú a la derecha.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when {
                busy && state != LyricsUiState.Loading -> "Actualizando…"
                loadedState == null -> ""
                synced -> "Sincronizada"
                else -> "Sin sincronizar"
            }
            Text(
                label.uppercase(),
                color = EighthTheme.TextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(synced, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                GlassIconButton(
                    Icons.Rounded.Tune,
                    if (showOffset) "Ocultar ajuste de sincronización" else "Ajustar sincronización",
                    onClick = { showOffset = !showOffset },
                    active = showOffset || offsetMs != 0L,
                    size = 34.dp,
                    iconSize = 17.dp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box {
                GlassIconButton(
                    Icons.Rounded.MoreHoriz,
                    "Opciones de letra",
                    onClick = { menuOpen = true },
                    enabled = !busy && pendingImportSong == null,
                    size = 34.dp,
                    iconSize = 18.dp,
                )
                GlassDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    GlassMenuItem(
                        label = "Importar .lrc",
                        icon = Icons.Rounded.FileOpen,
                        onClick = {
                            menuOpen = false
                            openImport()
                        },
                    )
                    // También con letra sin sincronizar: "Reintentar" busca una sincronizada.
                    GlassMenuItem(
                        label = if (loadedState != null) "Buscar versión sincronizada" else "Reintentar búsqueda",
                        icon = Icons.Rounded.Refresh,
                        enabled = !synced,
                        onClick = {
                            menuOpen = false
                            action = LyricsAction.Retry
                        },
                    )
                    GlassMenuItem(
                        label = "Editar manualmente",
                        icon = Icons.Rounded.Edit,
                        onClick = {
                            menuOpen = false
                            draft = loadedState?.lines?.joinToString("\n") { line ->
                                val ms = line.timestampMs.coerceAtLeast(0)
                                "[${ms / 60_000}:${(ms / 1000 % 60).toString().padStart(2, '0')}.${(ms % 1000).toString().padStart(3, '0')}]${line.text}"
                            }.orEmpty()
                            editing = true
                        },
                    )
                }
            }
        }
        AnimatedVisibility(
            synced && showOffset,
            enter = expandVertically(tween(260, easing = EighthTheme.EasingOutCubic)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(220, easing = EighthTheme.EasingInCubic)) + fadeOut(tween(150)),
        ) {
            OffsetControls(
                offsetMs = offsetMs,
                enabled = !busy,
                onChange = { action = LyricsAction.Offset(it.coerceIn(-60_000L, 60_000L)) },
            )
        }
        // El error se pinta UNA vez, aquí. El diálogo de edición también lo
        // mostraba, con lo que aparecía duplicado en la misma frame.
        AnimatedVisibility(error != null && !editing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                error.orEmpty(),
                color = EighthTheme.PrimarySoft,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EighthTheme.PrimarySoft.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Crossfade(
            targetState = state,
            animationSpec = tween(320),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "lyricsState",
        ) { current ->
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    LyricsUiState.Loading -> LyricsStatus(
                        icon = null,
                        title = "Buscando letra…",
                        subtitle = null,
                    )
                    LyricsUiState.Missing -> LyricsStatus(
                        icon = Icons.Rounded.Lyrics,
                        title = "Sin letra",
                        subtitle = "No se ha encontrado en LRCLIB ni NetEase.",
                    ) {
                        GlassPill(Icons.Rounded.Refresh, "Buscar de nuevo", enabled = !busy, onClick = { action = LyricsAction.Retry })
                        Spacer(Modifier.width(10.dp))
                        GlassPill(Icons.Rounded.FileOpen, "Importar .lrc", enabled = !busy && pendingImportSong == null, onClick = { openImport() })
                    }
                    is LyricsUiState.Loaded -> {
                        if (current.synced) {
                            SyncedLyricsList(current.lines, connection, offsetMs)
                        } else {
                            PlainLyrics(current.lines)
                        }
                    }
                }
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            containerColor = EighthTheme.PopupSurface,
            shape = RoundedCornerShape(EighthTheme.RadiusXL),
            title = { Text("Editar otra versión (.lrc)") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            // Bytes UTF-8, igual que validateLrcImport: contar
                            // caracteres dejaba escribir textos que luego fallaban.
                            if (it.encodeToByteArray().size <= MAX_LRC_IMPORT_BYTES) draft = it
                            else error = "La letra supera el límite de 256 KiB"
                        },
                        enabled = !busy,
                        label = { Text("[mm:ss.xxx] Texto") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    )
                    error?.let { Text(it, color = EighthTheme.PrimarySoft) }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { action = LyricsAction.Save(draft) }) { Text("Guardar") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text("Cancelar") } },
        )
    }
}

private sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data object Missing : LyricsUiState
    data class Loaded(val lines: List<LrcLine>, val synced: Boolean) : LyricsUiState
}
