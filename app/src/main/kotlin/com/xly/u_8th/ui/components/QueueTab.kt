package com.xly.u_8th.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/**
 * Cola de reproducción: cabecera con
 * shuffle, filas reordenables por tirador y eliminación.
 */
@Composable
fun QueueTab(
    queue: List<Song>,
    queueIndex: Int,
    queueLabel: String,
    isShuffled: Boolean,
    onToggleShuffle: () -> Unit,
    onReorder: (oldIndex: Int, newIndex: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onTapRow: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Espacio reservado bajo la última fila (p. ej. el mini player flotante). */
    bottomContentPadding: Dp = 8.dp,
) {
    val aura = animatedAura()
    if (queue.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    null,
                    tint = EighthTheme.TextTertiary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("Cola vacía", color = EighthTheme.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Abre un álbum y pulsa Reproducir", color = EighthTheme.TextSecondary, fontSize = 13.sp)
            }
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .glassCard(shape = RoundedCornerShape(EighthTheme.RadiusM))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    queueLabel.ifEmpty { "Cola actual" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    color = EighthTheme.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = if (isShuffled) "Restaurar orden original" else "Aleatorizar cola",
                    active = isShuffled,
                    onClick = onToggleShuffle,
                )
            }
        }
        QueueList(
            queue = queue,
            queueIndex = queueIndex,
            onReorder = onReorder,
            onRemove = onRemove,
            onTapRow = onTapRow,
            bottomContentPadding = bottomContentPadding,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        )
    }
}

@Composable
private fun QueueList(
    queue: List<Song>,
    queueIndex: Int,
    onReorder: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onTapRow: (Int) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Orden local: durante el arrastre solo se mueve esta lista y al soltar
    // se manda UN movimiento. Antes cada paso era un IPC y la fila cambiaba
    // de índice bajo el dedo, con lo que el gesto se cortaba.
    var localList by remember { mutableStateOf(queue) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(queue) {
        if (draggingIndex == null) localList = queue
    }

    fun dragStep(dy: Float) {
        val from = draggingIndex ?: return
        dragOffsetY += dy
        val info = listState.layoutInfo
        val dragged = info.visibleItemsInfo.firstOrNull { it.index == from } ?: return
        val center = dragged.offset + dragOffsetY + dragged.size / 2f
        val target = info.visibleItemsInfo
            .firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?.index
        if (target == null || target == from) return
        val reordered = localList.toMutableList()
        reordered.add(target, reordered.removeAt(from))
        localList = reordered
        dragOffsetY -= (target - from) * dragged.size
        draggingIndex = target
    }

    fun endDrag() {
        val from = dragFrom
        val to = draggingIndex
        dragFrom = null
        draggingIndex = null
        dragOffsetY = 0f
        if (from != null && to != null && from != to) {
            // RAW drop index (insertion point pre-removal): QueueManager hace el ajuste -1.
            onReorder(from, if (to > from) to + 1 else to)
        } else if (localList != queue) {
            localList = queue
        }
    }

    // `Song.queueEntryKey` recorre 0 until index para contar repeticiones:
    // invocarlo desde `key =` costs O(n²) en cada pasada de layout. Aquí se
    // precalcula una vez por identidad de cola.
    // Lista y claves fijadas en esta composición: el contenido del LazyColumn
    // se re-evalúa fuera de ella y, leyendo `localList` directamente, podía
    // ver la cola nueva (aleatorio total) con las claves de la vieja y salir
    // del índice.
    val rows = localList
    val entryKeys = remember(rows) { Song.queueEntryKeys(rows) }
    // "Sonando" por identidad de entrada: la lista local puede ir por
    // delante del índice del servicio.
    val playingKey = remember(queue, queueIndex) { Song.queueEntryKeys(queue).getOrNull(queueIndex) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 4.dp + bottomContentPadding,
        ),
        modifier = modifier,
    ) {
        items(
            count = rows.size,
            key = { entryKeys[it] },
        ) { index ->
            val song = rows[index]
            val isPlaying = entryKeys[index] == playingKey
            val isDragging = draggingIndex == index
            Box(
                Modifier
                    .padding(bottom = 5.dp)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationY = dragOffsetY
                            scaleX = 1.03f
                            scaleY = 1.03f
                            shape = androidx.compose.ui.graphics.RectangleShape
                            shadowElevation = 6.dp.toPx()
                        }
                    },
            ) {
                QueueRow(
                    song = song,
                    index = index,
                    isPlaying = isPlaying,
                    onTap = { onTapRow(index) },
                    onRemove = { onRemove(index) },
                    onDragStart = {
                        dragFrom = index
                        draggingIndex = index
                        dragOffsetY = 0f
                    },
                    onDrag = { amount -> dragStep(amount.y) },
                    onDragEnd = { endDrag() },
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
    onTap: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val aura = animatedAura()
    val accent = EighthTheme.accent(aura)
    // La fila conserva su composición al cambiar de índice (clave estable):
    // el detector no se reinicia y lee siempre los callbacks actuales.
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    Row(
        Modifier
            .fillMaxWidth()
            .glassCard(active = isPlaying, accent = accent)
            .pressClickable(pressedScale = 0.98f, onClick = onTap)
            .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArtThumb(song, isCurrent = isPlaying)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.displayTitle,
                fontSize = 14.sp,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = (-0.2).sp,
                color = if (isPlaying) accent else EighthTheme.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                songSubtitle(song),
                fontSize = 12.sp,
                color = EighthTheme.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // X + tirador: el tirador captura el arrastre (Main pass), el resto
        // de la fila queda libre para el tap.
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .pressClickable(pressedScale = 0.8f, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Quitar", tint = EighthTheme.TextTertiary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier
                .size(36.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            drag(amount)
                        },
                        onDragEnd = { dragEnd() },
                        onDragCancel = { dragEnd() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DragHandle, "Mover", tint = EighthTheme.TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}
