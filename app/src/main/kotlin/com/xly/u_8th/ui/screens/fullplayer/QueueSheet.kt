package com.xly.u_8th.ui.screens.fullplayer

import com.xly.u_8th.ui.components.SheetDivider
import com.xly.u_8th.ui.components.TintedIconBadge
import com.xly.u_8th.ui.components.GlassBottomSheet
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.components.glassCard
import com.xly.u_8th.ui.components.SongArtThumb
import com.xly.u_8th.ui.components.pressClickable
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.AppStrings
import com.xly.u_8th.util.fmtMs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bottom sheets owned by the full player: the playback queue (tap to jump,
 * long-press or handle drag to reorder, X to remove) and the best-effort
 * artist albums sheet (the primary artist flow lives in the main screen
 * cluster, so albums here come from the played-songs catalog).
 */

@Composable
fun QueueSheetForFullPlayer(onDismiss: () -> Unit) {
    val connection = rememberPlayerConnection()
    val queue by PlayerBus.queue.collectAsStateWithLifecycle()
    val queueIndex by PlayerBus.queueIndex.collectAsStateWithLifecycle()
    val shuffleEnabled by PlayerBus.shuffleEnabled.collectAsStateWithLifecycle()
    val queueLabel by PlayerBus.queueLabel.collectAsStateWithLifecycle()
    val aura = animatedAura()
    // Abre con la canción que suena arriba (con una ya escuchada encima).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (queueIndex - 1).coerceAtLeast(0))

    // Orden local: durante el arrastre solo se mueve esta lista y al soltar
    // se manda UN movimiento al servicio. Antes cada paso era un IPC y la
    // fila se re-indexaba bajo el dedo, cancelando el gesto.
    var localList by remember { mutableStateOf(PlayerBus.queue.value) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }

    // Mientras hay arrastre se ignora la cola entrante; al soltar, el eco del
    // servicio trae el mismo orden que ya muestra la lista local.
    LaunchedEffect(queue) {
        if (draggingIndex == null) localList = queue
    }

    fun startDrag(from: Int) {
        dragFrom = from
        draggingIndex = from
        dragDelta = 0f
    }

    fun endDrag() {
        val from = dragFrom
        val to = draggingIndex
        dragFrom = null
        draggingIndex = null
        dragDelta = 0f
        if (from != null && to != null && from != to) {
            // Raw drop index: QueueManager hace el ajuste -1.
            connection.launch { move(from, if (from < to) to + 1 else to) }
        } else if (localList != PlayerBus.queue.value) {
            localList = PlayerBus.queue.value
        }
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
            // Keep the card under the finger after its slot jumped.
            dragDelta -= (target - from) * dragged.size
            draggingIndex = target
        }
    }

    // "Sonando" por identidad de entrada, no por índice: la lista local puede
    // ir por delante del índice del servicio.
    val playingKey = remember(queue, queueIndex) { Song.queueEntryKeys(queue).getOrNull(queueIndex) }

    GlassBottomSheet(onDismiss = onDismiss, heightFraction = 0.66f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "A continuación",
                    color = EighthTheme.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
                val remainingMs = queue.drop(queueIndex + 1).sumOf { it.durationMs ?: 0L }
                Text(
                    buildString {
                        if (queueLabel.isNotBlank()) append(queueLabel).append(" · ")
                        append("${(queueIndex + 1).coerceAtMost(queue.size)} de ${queue.size}")
                        if (remainingMs > 0) append(" · ").append(remainingMs / 60_000).append(" min")
                    },
                    color = EighthTheme.TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            GlassIconButton(
                icon = Icons.Rounded.Shuffle,
                contentDescription = if (shuffleEnabled) "Restaurar orden original" else "Aleatorizar cola",
                onClick = { connection.launch { setShuffled(!shuffleEnabled) } },
                active = shuffleEnabled,
                size = EighthTheme.IconButtonM,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Claves precalculadas: queueEntryKey() es O(index) y LazyList pide la
        // clave de cada elemento en cada pasada de layout.
        // Lista fijada en esta composición (ver QueueTab): el contenido del
        // LazyColumn no debe leer `localList` por su cuenta.
        val rows = localList
        val entryKeys = remember(rows) { Song.queueEntryKeys(rows) }
        val playingIndex = entryKeys.indexOf(playingKey)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(
                rows,
                key = { index, _ -> entryKeys[index] },
            ) { index, song ->
                QueueRow(
                    song = song,
                    index = index,
                    isPlaying = entryKeys[index] == playingKey,
                    played = playingIndex >= 0 && index < playingIndex,
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

@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    played: Boolean,
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
    val accent = EighthTheme.accent(aura)
    // La fila conserva su composición al cambiar de índice (clave estable):
    // los detectores no se reinician y leen siempre los callbacks actuales.
    val startDrag by rememberUpdatedState(onStartDrag)
    val dragStep by rememberUpdatedState(onDragStep)
    val endDrag by rememberUpdatedState(onEndDrag)
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .padding(bottom = 2.dp)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                // Lo ya escuchado queda atenuado por encima de la actual.
                alpha = if (played && !isDragging) 0.45f else 1f
                if (isDragging) {
                    translationY = dragOffset()
                    val s = 1.03f
                    scaleX = s
                    scaleY = s
                }
            }
            // Combined tap + long-press-drag detector. Runs only when no child
            // (remove button) consumed the down, so buttons never start drags.
            .pointerInput(Unit) {
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
                        startDrag()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                dragStep(change.positionChange().y)
                            }
                        } finally {
                            endDrag()
                        }
                    } else if (gesture == 1) {
                        tap()
                    }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // Filas planas: solo la que suena (o la que se arrastra)
                // lleva tarjeta, como en las listas de Apple Music.
                .then(
                    if (isPlaying || isDragging) {
                        Modifier.glassCard(active = isPlaying, accent = accent, shape = rowShape)
                    } else {
                        Modifier.clip(rowShape)
                    },
                )
                .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongArtThumb(song, isCurrent = isPlaying, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.displayTitle,
                    color = if (isPlaying) accent else EighthTheme.TextPrimary,
                    fontSize = 14.sp,
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
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .pressClickable(pressedScale = 0.8f, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, "Quitar de la cola", tint = EighthTheme.TextTertiary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { startDrag() },
                            onDrag = { change, amount ->
                                change.consume()
                                dragStep(amount.y)
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = "Reordenar",
                    tint = EighthTheme.TextTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
