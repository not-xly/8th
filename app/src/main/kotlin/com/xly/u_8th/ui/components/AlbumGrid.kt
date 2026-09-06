package com.xly.u_8th.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

private fun columnsForWidth(width: Float): Int =
    when {
        width <= 500f -> 5
        width <= 700f -> 6
        width <= 1000f -> 8
        else -> (width / 115f).toInt().coerceIn(9, 16)
    }

/**
 * Grid de álbumes (port of widgets/album_grid_view.dart): selección por
 * long-press, detalle por tap y modo reordenar con arrastre en vivo
 * (el commit al modelo ocurre al soltar).
 */
@Composable
fun AlbumGrid(
    grouped: Map<String, List<Song>>,
    sortedAlbumKeys: List<String>,
    selectedAlbums: Set<String>,
    isReordering: Boolean,
    onToggleAlbum: (String) -> Unit,
    onOpenDetails: (String, List<Song>) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    // Orden local reordenable en vivo; fuera del modo reordenar refleja
    // siempre la fuente de verdad (y parte fresca al entrar).
    var order by remember { mutableStateOf(sortedAlbumKeys) }
    LaunchedEffect(sortedAlbumKeys, isReordering) {
        if (!isReordering) order = sortedAlbumKeys
    }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    var dragStartedOrder by remember { mutableStateOf<List<String>?>(null) }

    BoxWithConstraints(modifier) {
        val columns = columnsForWidth(constraints.maxWidth.toFloat())
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = 12.dp,
                bottom = if (isReordering) 40.dp else 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = !isReordering,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = order.size, key = { order[it] }) { index ->
                val albumName = order[index]
                val albumSongs = grouped[albumName] ?: emptyList()
                val coverSong = remember(albumSongs) {
                    albumSongs.firstOrNull { it.albumArt != null } ?: albumSongs.firstOrNull()
                }
                val isDragging = draggingKey == albumName
                val isHovered = hoveredKey == albumName && draggingKey != null && !isDragging
                if (isReordering) {
                    ReorderCell(
                        albumName = albumName,
                        coverSong = coverSong,
                        index = index,
                        isDragging = isDragging,
                        isHovered = isHovered,
                        dragOffset = dragOffset,
                        onDragStart = {
                            draggingKey = albumName
                            hoveredKey = null
                            dragStartedOrder = order
                        },
                        onDrag = { amount ->
                            if (draggingKey != albumName) return@ReorderCell
                            dragOffset += amount
                            val info = gridState.layoutInfo
                            val dragged = info.visibleItemsInfo.firstOrNull { it.key == albumName }
                                ?: return@ReorderCell
                            val center = Offset(
                                dragged.offset.x + dragOffset.x + dragged.size.width / 2f,
                                dragged.offset.y + dragOffset.y + dragged.size.height / 2f,
                            )
                            val target = info.visibleItemsInfo.firstOrNull { item ->
                                center.x >= item.offset.x && center.x <= item.offset.x + item.size.width &&
                                    center.y >= item.offset.y && center.y <= item.offset.y + item.size.height
                            } ?: return@ReorderCell
                            val targetKey = target.key as? String ?: return@ReorderCell
                            if (targetKey == albumName) {
                                hoveredKey = null
                                return@ReorderCell
                            }
                            val from = order.indexOf(albumName)
                            if (from == -1) return@ReorderCell
                            hoveredKey = targetKey
                            val insertAfter = center.x > target.offset.x + target.size.width / 2f
                            var to = order.indexOf(targetKey) + if (insertAfter) 1 else 0
                            if (from < to) to--
                            if (to == from) return@ReorderCell
                            // Compensa el salto de layout para que la celda
                            // siga bajo el dedo tras el reordenado en vivo.
                            val stepX = dragged.size.width + info.mainAxisItemSpacing
                            val stepY = dragged.size.height + info.mainAxisItemSpacing
                            val oldCol = dragged.offset.x / stepX.toFloat()
                            val oldRow = dragged.offset.y / stepY.toFloat()
                            val newOrder = order.toMutableList().apply { removeAt(from); add(to, albumName) }
                            order = newOrder
                            dragOffset = Offset(
                                dragOffset.x + (oldCol - (to % columns)) * stepX,
                                dragOffset.y + (oldRow - (to / columns)) * stepY,
                            )
                        },
                        onDragEnd = {
                            if (draggingKey == albumName) {
                                val started = dragStartedOrder
                                if (started != null && order != started) onReorder(order)
                                draggingKey = null
                                hoveredKey = null
                                dragOffset = Offset.Zero
                                dragStartedOrder = null
                            }
                        },
                    )
                } else {
                    NormalCell(
                        albumName = albumName,
                        coverSong = coverSong,
                        isSelected = albumName in selectedAlbums,
                        onTap = {
                            if (selectedAlbums.isNotEmpty()) {
                                onToggleAlbum(albumName)
                            } else {
                                onOpenDetails(albumName, albumSongs)
                            }
                        },
                        onLongPress = { onToggleAlbum(albumName) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NormalCell(
    albumName: String,
    coverSong: Song?,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val aura = animatedAura()
    val fill by animateColorAsState(
        targetValue = if (isSelected) EighthTheme.SelectedCard.copy(alpha = 0.92f)
        else EighthTheme.GlassSurface.copy(alpha = 0.54f),
        animationSpec = tween(420, easing = EighthTheme.EasingStandard),
        label = "albumCellFill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) aura.copy(alpha = 0.88f) else EighthTheme.GlassBorder,
        animationSpec = tween(420, easing = EighthTheme.EasingStandard),
        label = "albumCellBorder",
    )
    val art = rememberArtBitmap(coverSong?.albumArt, sample = 2)
    Box(
        Modifier
            .aspectRatio(1f)
            .shadow(
                8.dp, RoundedCornerShape(EighthTheme.RadiusM),
                spotColor = Color.Black.copy(alpha = 0.18f), ambientColor = Color.Transparent,
            )
            .background(fill, RoundedCornerShape(EighthTheme.RadiusM))
            .border(
                BorderStroke(if (isSelected) 1.dp else 0.7.dp, borderColor),
                RoundedCornerShape(EighthTheme.RadiusM),
            )
            .combinedClickable(
                interactionSource = remember(albumName) { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .clip(RoundedCornerShape(EighthTheme.RadiusM - 1.dp)),
    ) {
        Crossfade(
            targetState = art,
            animationSpec = tween(450),
            label = "albumArt",
            modifier = Modifier.fillMaxSize(),
        ) { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = albumName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(EighthTheme.GlassSurfaceLight.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .background(aura.copy(alpha = 0.10f), CircleShape)
                                .border(BorderStroke(0.7.dp, aura.copy(alpha = 0.14f)), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Album, null, tint = aura, modifier = Modifier.size(16.dp))
                        }
                        Box(Modifier.size(4.dp))
                        Text(
                            albumName,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                            color = EighthTheme.TextSecondary,
                            lineHeight = 8.3.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        if (isSelected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(aura.copy(alpha = 0.58f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Celda en modo reordenar: jiggle iOS; la celda arrastrada vuela con el dedo. */
@Composable
private fun ReorderCell(
    albumName: String,
    coverSong: Song?,
    index: Int,
    isDragging: Boolean,
    isHovered: Boolean,
    dragOffset: Offset,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val aura = animatedAura()
    val transition = rememberInfiniteTransition(label = "jiggle")
    val jiggle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
        label = "jiggleAngle",
    )
    val angle = (jiggle - 0.5f) * 0.035f * (if (index % 2 == 0) 1f else -1f)
    val art = rememberArtBitmap(coverSong?.albumArt, sample = 2)
    Box(
        Modifier
            .aspectRatio(1f)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.08f
                    scaleY = 1.08f
                    shape = RectangleShape
                    shadowElevation = 10.dp.toPx()
                } else {
                    rotationZ = angle
                }
            }
            .pointerInput(albumName) {
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
    ) {
        val borderColor = if (isHovered && !isDragging) aura.copy(alpha = 0.8f) else EighthTheme.GlassBorder
        val borderWidth = if (isHovered && !isDragging) 1.2.dp else 0.7.dp
        Box(
            Modifier
                .fillMaxSize()
                .background(EighthTheme.GlassSurface, RoundedCornerShape(EighthTheme.RadiusM))
                .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(EighthTheme.RadiusM))
                .clip(RoundedCornerShape(EighthTheme.RadiusM - 1.dp)),
        ) {
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = albumName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(EighthTheme.GlassSurfaceLight.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Album,
                        null,
                        tint = aura.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
