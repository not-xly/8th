package com.xly.u_8th.ui.screens.fullplayer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xly.u_8th.data.AlbumGrouper
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlaybackCatalog
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.ui.theme.lerpColor
import com.xly.u_8th.util.AppStrings
import com.xly.u_8th.util.fmtMs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bottom sheets owned by the full player: the playback queue (tap to jump,
 * long-press or handle drag to reorder, X to remove) and the best-effort
 * artist albums sheet (the primary artist flow lives in the main screen
 * cluster, so albums here come from the played-songs catalog).
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheetForFullPlayer(onDismiss: () -> Unit) {
    val connection = rememberPlayerConnection()
    val queue by PlayerBus.queue.collectAsState()
    val queueIndex by PlayerBus.queueIndex.collectAsState()
    val shuffleEnabled by PlayerBus.shuffleEnabled.collectAsState()
    val aura = animatedAura()
    val listState = rememberLazyListState()

    // Local order during a drag: binder echoes of our own moves are ignored
    // so the rows under the finger never snap back mid-gesture.
    var localList by remember { mutableStateOf(PlayerBus.queue.value) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(queue) {
        if (draggingIndex == null) localList = queue
    }

    fun startDrag(from: Int) {
        draggingIndex = from
        dragDelta = 0f
    }

    fun endDrag() {
        draggingIndex = null
        dragDelta = 0f
    }

    fun dragStep(dy: Float) {
        val from = draggingIndex ?: return
        dragDelta += dy
        val info = listState.layoutInfo
        val dragged = info.visibleItemsInfo.firstOrNull { it.index == from } ?: return
        val center = dragged.offset + dragged.size / 2f + dragDelta
        val target = info.visibleItemsInfo
            .firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?.index
        if (target != null && target != from) {
            val reordered = localList.toMutableList()
            reordered.add(target, reordered.removeAt(from))
            localList = reordered
            // QueueManager.reorderQueue does the -1 adjustment internally
            // (raw drop index; QueueManager adjusts internally).
            connection.launch { move(from, if (from < target) target + 1 else target) }
            // Keep the card under the finger after its slot jumped.
            dragDelta -= (target - from) * dragged.size
            draggingIndex = target
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EighthTheme.GlassSurface.copy(alpha = 0.78f),
        contentColor = EighthTheme.TextPrimary,
        shape = RoundedCornerShape(topStart = EighthTheme.RadiusL, topEnd = EighthTheme.RadiusL),
        dragHandle = null,
        modifier = Modifier.fillMaxHeight(0.62f),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // Custom drag handle.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(aura.copy(alpha = 0.14f))
                        .border(BorderStroke(0.8.dp, aura.copy(alpha = 0.16f)), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = aura,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Cola de reproducción",
                    color = EighthTheme.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.weight(1f),
                )
                GlassIconButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = if (shuffleEnabled) "Restaurar orden original" else "Aleatorizar cola",
                    onClick = { connection.launch { setShuffled(!shuffleEnabled) } },
                    active = shuffleEnabled,
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(EighthTheme.GlassBorder),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(
                    localList,
                    key = { index, _ -> Song.queueEntryKey(localList, index) },
                ) { index, song ->
                    QueueRow(
                        song = song,
                        index = index,
                        isPlaying = index == queueIndex,
                        aura = aura,
                        isDragging = draggingIndex == index,
                        dragOffset = { dragDelta },
                        onStartDrag = { startDrag(index) },
                        onDragStep = { dragStep(it) },
                        onEndDrag = { endDrag() },
                        onTap = { connection.launch { seekToIndex(index) } },
                        onRemove = { connection.launch { removeAt(index) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    aura: Color,
    isDragging: Boolean,
    dragOffset: () -> Float,
    onStartDrag: () -> Unit,
    onDragStep: (Float) -> Unit,
    onEndDrag: () -> Unit,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    val rowShape = RoundedCornerShape(EighthTheme.RadiusM)
    Box(
        Modifier
            .padding(bottom = 5.dp)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationY = dragOffset()
                    val s = 1.03f
                    scaleX = s
                    scaleY = s
                }
            }
            // Combined tap + long-press-drag detector. Runs only when no child
            // (remove button) consumed the down, so buttons never start drags.
            .pointerInput(index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    var gesture = 0 // 1 = tap; 0 = scroll/cancel
                    val timedOut: Unit? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var moved = 0f
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                if (!change.isConsumed) gesture = 1
                                break
                            }
                            moved += change.positionChange().getDistance()
                            if (moved > slop) break
                        }
                    }
                    if (timedOut == null) {
                        // Long press: drag mode. Consume so the list never scrolls.
                        onStartDrag()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                onDragStep(change.positionChange().y)
                            }
                        } finally {
                            onEndDrag()
                        }
                    } else if (gesture == 1) {
                        onTap()
                    }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (isPlaying) Modifier.shadow(10.dp, rowShape, clip = false) else Modifier)
                .clip(rowShape)
                .background(
                    if (isPlaying) {
                        lerpColor(EighthTheme.SelectedCard, aura, 0.18f).copy(alpha = 0.92f)
                    } else {
                        EighthTheme.GlassSurface.copy(alpha = 0.62f)
                    },
                )
                .border(
                    BorderStroke(
                        if (isPlaying) 1.dp else 0.8.dp,
                        if (isPlaying) aura.copy(alpha = 0.88f) else EighthTheme.GlassBorder,
                    ),
                    rowShape,
                )
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPlaying) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(aura.copy(alpha = 0.14f))
                        .border(BorderStroke(0.8.dp, aura.copy(alpha = 0.22f)), RoundedCornerShape(9.dp))
                        .padding(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = EighthTheme.softAura(aura),
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        color = EighthTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.displayTitle,
                    color = if (isPlaying) EighthTheme.desaturated(aura) else EighthTheme.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val who = song.artist ?: song.album ?: AppStrings.UNKNOWN
                val subtitle = song.durationMs?.takeIf { it > 0 }?.let { "$who • ${fmtMs(it)}" } ?: who
                Text(
                    subtitle,
                    color = EighthTheme.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GlassIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Quitar de la cola",
                onClick = onRemove,
                radius = 10.dp,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .pointerInput(index) {
                        detectDragGestures(
                            onDragStart = { onStartDrag() },
                            onDrag = { change, amount ->
                                change.consume()
                                onDragStep(amount.y)
                            },
                            onDragEnd = { onEndDrag() },
                            onDragCancel = { onEndDrag() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = "Reordenar",
                    tint = EighthTheme.TextTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Artist albums sheet, best-effort port of MusicPlayerScreen._showArtistAlbums:
 * albums are computed from the played-songs catalog because the grouped
 * library lives in the main screen's ViewModel (owned elsewhere).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistSheetForFullPlayer(
    artist: String,
    onDismiss: () -> Unit,
) {
    val connection = rememberPlayerConnection()
    val aura = animatedAura()
    val albums = remember(artist) {
        val wanted = Artists.parseArtists(artist)
        AlbumGrouper.groupByAlbum(PlaybackCatalog.contents())
            .filter { (_, songs) -> Artists.albumMatchesArtist(songs, wanted) }
            .entries
            .toList()
    }
    val displayArtist = Artists.parseArtists(artist).firstOrNull() ?: artist

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EighthTheme.GlassSurface.copy(alpha = 0.78f),
        contentColor = EighthTheme.TextPrimary,
        shape = RoundedCornerShape(topStart = EighthTheme.RadiusL, topEnd = EighthTheme.RadiusL),
        dragHandle = null,
        modifier = Modifier.fillMaxHeight(0.6f),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(aura.copy(alpha = 0.14f))
                        .border(BorderStroke(0.8.dp, aura.copy(alpha = 0.16f)), RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = aura,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayArtist,
                        color = EighthTheme.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${albums.size} ${if (albums.size == 1) "álbum" else "álbumes"}",
                        color = EighthTheme.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(EighthTheme.GlassBorder),
            )
            if (albums.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Sin álbumes de este artista",
                        color = EighthTheme.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 12.dp),
                ) {
                    itemsIndexed(albums, key = { i, entry -> entry.key }) { _, entry ->
                        val albumName = entry.key
                        val albumSongs = entry.value
                        val art = rememberDecodedArt(
                            albumSongs.firstOrNull { it.albumArt != null }?.albumArt,
                        )
                        Row(
                            Modifier
                                .padding(bottom = 6.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(EighthTheme.RadiusM))
                                .background(EighthTheme.GlassSurface.copy(alpha = 0.58f))
                                .border(
                                    BorderStroke(0.8.dp, EighthTheme.GlassBorder),
                                    RoundedCornerShape(EighthTheme.RadiusM),
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    onDismiss()
                                    connection.launch { playQueue(albumSongs, label = albumName) }
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (art != null) {
                                Image(
                                    bitmap = art,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(EighthTheme.GlassSurfaceLight.copy(alpha = 0.9f))
                                        .border(
                                            BorderStroke(0.7.dp, EighthTheme.GlassBorder),
                                            RoundedCornerShape(10.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.Album,
                                        contentDescription = null,
                                        tint = EighthTheme.TextSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    albumName,
                                    color = EighthTheme.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    letterSpacing = (-0.2).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${albumSongs.size} ${if (albumSongs.size == 1) "canción" else "canciones"}",
                                    color = EighthTheme.TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = EighthTheme.TextTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
