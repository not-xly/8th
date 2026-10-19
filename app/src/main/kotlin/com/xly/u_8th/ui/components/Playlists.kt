package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xly.u_8th.domain.Playlist
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/** Canciones de [playlist] que siguen en la biblioteca, en su orden. */
fun Playlist.resolve(songsByPath: Map<String, Song>): List<Song> = songPaths.mapNotNull { songsByPath[it] }

private fun summaryOf(songs: List<Song>): String {
    val totalMs = songs.sumOf { it.durationMs ?: 0L }
    return buildString {
        append(if (songs.size == 1) "1 canción" else "${songs.size} canciones")
        if (totalMs > 0) append(" · ").append(totalMs / 60_000).append(" min")
    }
}

// ---------------------------------------------------------------------------
// Pestaña
// ---------------------------------------------------------------------------

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    songsByPath: Map<String, Song>,
    onCreate: () -> Unit,
    onOpen: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 8.dp,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp + bottomContentPadding),
    ) {
        item(key = "create") { NewPlaylistRow(onCreate) }
        if (playlists.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistPlay,
                        null,
                        tint = EighthTheme.TextTertiary,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Sin playlists", color = EighthTheme.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Crea una y añade canciones desde su menú ···",
                        color = EighthTheme.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            val songs = remember(playlist, songsByPath) { playlist.resolve(songsByPath) }
            PlaylistRow(playlist, songs, onClick = { onOpen(playlist) })
        }
    }
}

@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    val aura = animatedAura()
    val accent = EighthTheme.accent(aura)
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .glassCard(active = true, accent = accent)
            .pressClickable(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Nueva playlist",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            color = EighthTheme.TextPrimary,
        )
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, songs: List<Song>, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 7.dp)
            .fillMaxWidth()
            .glassCard()
            .pressClickable(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(songs, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                color = EighthTheme.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(summaryOf(songs), fontSize = 12.sp, color = EighthTheme.TextSecondary, maxLines = 1)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = EighthTheme.TextTertiary, modifier = Modifier.size(20.dp))
    }
}

/** Portada: la primera carátula disponible de la lista, o un icono. */
@Composable
private fun PlaylistCover(songs: List<Song>, size: Dp) {
    val aura = animatedAura()
    val cover = rememberArtBitmap(songs.firstOrNull { it.albumArt != null }?.albumArt, size)
    val shape = RoundedCornerShape(size * 0.2f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(EighthTheme.GlassSurfaceLight)
            .border(BorderStroke(0.7.dp, EighthTheme.GlassBorderStrong), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = EighthTheme.accent(aura), modifier = Modifier.size(size * 0.42f))
        }
    }
}

// ---------------------------------------------------------------------------
// Ficha de playlist
// ---------------------------------------------------------------------------

@Composable
fun PlaylistDetailsSheet(
    playlist: Playlist,
    songs: List<Song>,
    currentSongPath: String?,
    onPlay: (Int) -> Unit,
    onPlayShuffled: () -> Unit,
    onAddToNext: (Song) -> Unit,
    onAddToEnd: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val aura = animatedAura()
    GlassBottomSheet(onDismiss = onDismiss, heightFraction = 1f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.shadow(12.dp, RoundedCornerShape(15.dp), spotColor = aura.copy(alpha = 0.4f), ambientColor = Color.Transparent),
            ) {
                PlaylistCover(songs, 76.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = EighthTheme.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Playlist", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = EighthTheme.softAura(aura, 0.8f))
                Text(summaryOf(songs), fontSize = 12.sp, color = EighthTheme.TextTertiary)
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                GlassIconButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = "Opciones de la playlist",
                    onClick = { menuOpen = true },
                    size = EighthTheme.IconButtonM,
                )
                GlassDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    GlassMenuItem(
                        label = "Renombrar",
                        icon = Icons.Rounded.Edit,
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    GlassMenuItem(
                        label = "Eliminar playlist",
                        icon = Icons.Rounded.DeleteOutline,
                        destructive = true,
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row {
            PillButton(
                label = "Reproducir",
                onClick = {
                    onDismiss()
                    onPlay(0)
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.PlayArrow,
                primary = true,
                enabled = songs.isNotEmpty(),
            )
            Spacer(Modifier.width(10.dp))
            PillButton(
                label = "Aleatorio",
                onClick = {
                    onDismiss()
                    onPlayShuffled()
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Shuffle,
                enabled = songs.isNotEmpty(),
            )
        }
        Spacer(Modifier.height(12.dp))
        SheetDivider()
        Spacer(Modifier.height(12.dp))
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Añade canciones con «Añadir a playlist»\nen el menú ··· de cualquier canción",
                    color = EighthTheme.TextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(
                    count = songs.size,
                    key = { index -> songs[index].path },
                ) { index ->
                    val song = songs[index]
                    SongTile(
                        song = song,
                        isCurrent = currentSongPath != null && currentSongPath == song.playUri,
                        onTap = {
                            onDismiss()
                            onPlay(index)
                        },
                        onAddToNext = { onAddToNext(song) },
                        onAddToEnd = { onAddToEnd(song) },
                        onRemoveFromPlaylist = { onRemove(song) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Diálogos
// ---------------------------------------------------------------------------

/** Tarjeta de cristal centrada con scrim: tocar fuera cierra. */
@Composable
internal fun GlassDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 40.dp)
                    .fillMaxWidth()
                    .glassPopup()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume el toque dentro de la tarjeta */ }
                    .padding(vertical = 8.dp),
            ) { content() }
        }
    }
}

@Composable
internal fun DialogTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        color = EighthTheme.TextTertiary,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
internal fun DialogButtons(
    confirmLabel: String,
    confirmEnabled: Boolean,
    confirmColor: Color? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PillButton("Cancelar", onDismiss, Modifier.weight(1f))
        PillButton(
            label = confirmLabel,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            primary = true,
            enabled = confirmEnabled,
            color = confirmColor,
        )
    }
}

/** Pide un nombre (crear o renombrar). */
@Composable
fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val aura = animatedAura()
    var value by remember {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length)))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // La ventana del diálogo tarda un frame en tener foco: sin la espera
        // el campo se enfocaba pero el teclado no salía.
        delay(120)
        focus.requestFocus()
        keyboard?.show()
    }
    val canConfirm = value.text.isNotBlank()
    fun confirm() {
        if (!canConfirm) return
        onConfirm(value.text.trim())
        onDismiss()
    }
    GlassDialog(onDismiss) {
        DialogTitle(title)
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = EighthTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(aura),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .fillMaxWidth()
                .focusRequester(focus),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clip(RoundedCornerShape(EighthTheme.RadiusM))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(BorderStroke(0.8.dp, EighthTheme.accent(aura).copy(alpha = 0.5f)), RoundedCornerShape(EighthTheme.RadiusM))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.text.isEmpty()) {
                        Text("Nombre de la playlist", color = EighthTheme.TextTertiary, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
        DialogButtons(
            confirmLabel = confirmLabel,
            confirmEnabled = canConfirm,
            onConfirm = ::confirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun DeletePlaylistDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismiss) {
        DialogTitle("Eliminar playlist")
        Text(
            "¿Eliminar «$name»? Las canciones seguirán en tu biblioteca.",
            fontSize = 14.sp,
            color = EighthTheme.TextPrimary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        DialogButtons(
            confirmLabel = "Eliminar",
            confirmEnabled = true,
            confirmColor = Color(0xFFE5484D),
            onConfirm = {
                onConfirm()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Elegir playlist destino para [count] canciones. "Nueva playlist" pide el
 * nombre en el mismo diálogo y crea la lista ya con las canciones.
 */
@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    count: Int,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    if (creating) {
        PlaylistNameDialog(
            title = "Nueva playlist",
            initialName = "",
            confirmLabel = "Crear",
            onConfirm = onCreate,
            onDismiss = onDismiss,
        )
        return
    }
    val aura = animatedAura()
    GlassDialog(onDismiss) {
        DialogTitle(if (count == 1) "Añadir a playlist" else "Añadir $count canciones a playlist")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { creating = true }
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // El aura puede ser casi gris (portadas oscuras): texto claro siempre.
            Icon(Icons.Rounded.Add, null, tint = EighthTheme.accent(aura), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Nueva playlist", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = EighthTheme.TextPrimary)
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onPick(playlist)
                            onDismiss()
                        }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistAdd,
                        null,
                        tint = EighthTheme.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        playlist.name,
                        fontSize = 14.sp,
                        color = EighthTheme.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${playlist.songPaths.size}",
                        fontSize = 12.sp,
                        color = EighthTheme.TextTertiary,
                    )
                }
            }
        }
    }
}
