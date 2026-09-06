package com.xly.u_8th.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.fmtMs

/**
 * Cola de reproducción (port of widgets/queue_view.dart): cabecera con
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
) {
    val aura = animatedAura()
    if (queue.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Cola vacía",
                color = EighthTheme.TextSecondary,
                fontSize = 15.sp,
            )
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(EighthTheme.GlassSurface.copy(alpha = 0.56f), RoundedCornerShape(12.dp))
                    .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(12.dp))
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
                    activeTint = aura,
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // Movimiento enviado al servicio aún no reflejado en PlayerBus.queue.
    var pendingComp by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val appliedComp = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Al llegar la cola reordenada del servicio, compensa la traslación en
    // la misma frame para que la fila siga bajo el dedo (latencia IPC).
    val pending = pendingComp
    if (pending != null && pending != appliedComp.value && draggingIndex != null) {
        val info = listState.layoutInfo
        val step = info.visibleItemsInfo.firstOrNull()?.let { it.size + info.mainAxisItemSpacing } ?: 0
        if (step > 0) {
            dragOffsetY += (pending.first - pending.second) * step
            draggingIndex = pending.second
            appliedComp.value = pending
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = modifier,
    ) {
        items(
            count = queue.size,
            key = { Song.queueEntryKey(queue, it) },
        ) { index ->
            val song = queue[index]
            val isPlaying = index == queueIndex
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
                    onDragStart = { draggingIndex = index; dragOffsetY = 0f },
                    onDrag = { amount ->
                        if (draggingIndex != index) return@QueueRow
                        dragOffsetY += amount.y
                        val info = listState.layoutInfo
                        val dragged = info.visibleItemsInfo.firstOrNull { it.index == index }
                            ?: return@QueueRow
                        val center = dragged.offset + dragOffsetY + dragged.size / 2f
                        val target = info.visibleItemsInfo.firstOrNull { item ->
                            center >= item.offset && center <= item.offset + item.size
                        } ?: return@QueueRow
                        if (target.index == index) return@QueueRow
                        // RAW drop index estilo Flutter (punto de inserción
                        // pre-removal): QueueManager hace el ajuste -1.
                        val newIndex = if (target.index > index) target.index + 1 else target.index
                        val finalPos = target.index
                        onReorder(index, newIndex)
                        pendingComp = index to finalPos
                    },
                    onDragEnd = {
                        if (draggingIndex == index) {
                            draggingIndex = null
                            dragOffsetY = 0f
                            pendingComp = null
                            appliedComp.value = null
                        }
                    },
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
    val auraSoft = EighthTheme.desaturated(aura)
    val fill by animateColorAsState(
        targetValue = if (isPlaying) lerp(EighthTheme.SelectedCard, aura, 0.18f).copy(alpha = 0.92f)
        else EighthTheme.GlassSurface.copy(alpha = 0.62f),
        animationSpec = tween(420, easing = EighthTheme.EasingStandard),
        label = "queueFill",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(EighthTheme.RadiusM))
            .then(
                if (isPlaying) {
                    Modifier.border(
                        BorderStroke(1.dp, aura.copy(alpha = 0.88f)),
                        RoundedCornerShape(EighthTheme.RadiusM),
                    )
                } else {
                    Modifier.border(
                        BorderStroke(0.7.dp, EighthTheme.GlassBorder),
                        RoundedCornerShape(EighthTheme.RadiusM),
                    )
                }
            )
            .clickable(
                interactionSource = remember(index) { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPlaying) {
            Box(
                Modifier
                    .padding(0.dp)
                    .background(aura.copy(alpha = 0.14f), RoundedCornerShape(9.dp))
                    .border(BorderStroke(0.7.dp, aura.copy(alpha = 0.22f)), RoundedCornerShape(9.dp))
                    .padding(7.dp),
            ) {
                Icon(Icons.Rounded.Equalizer, null, tint = auraSoft, modifier = Modifier.size(16.dp))
            }
        } else {
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(9.dp))
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
                fontSize = 14.sp,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = (-0.2).sp,
                color = if (isPlaying) auraSoft else EighthTheme.TextPrimary,
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
                .background(Color.White.copy(alpha = 0.06f))
                .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Quitar", tint = EighthTheme.TextSecondary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(width = 28.dp, height = 36.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DragHandle, "Mover", tint = EighthTheme.TextTertiary, modifier = Modifier.size(16.dp))
        }
    }
}
