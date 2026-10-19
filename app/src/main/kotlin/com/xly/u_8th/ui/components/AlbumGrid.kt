package com.xly.u_8th.ui.components

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import kotlin.math.ceil

private const val GRID_H_PAD_DP = 12f
private const val GRID_GAP_DP = 8f
/** Tope de espera a las portadas en caché antes de enseñar el grid. */
private const val COVERS_MAX_WAIT_MS = 1500L

/**
 * "Pantalla" (auto): el menor número de columnas con el que TODOS los
 * álbumes caben en el alto disponible, sin scroll. Busca desde menos
 * columnas (celdas grandes) hacia más y devuelve la primera que cabe; si
 * ninguna cabe (biblioteca enorme), la que menos desborda.
 */
private fun fitAllColumns(albumCount: Int, widthDp: Float, heightDp: Float): Int {
    if (albumCount <= 0 || widthDp <= 0f) return 5
    val usableW = widthDp - 2 * GRID_H_PAD_DP
    var best = 16
    var bestOverflow = Float.MAX_VALUE
    for (c in 3..16) {
        val cell = (usableW - (c - 1) * GRID_GAP_DP) / c
        if (cell <= 1f) continue
        val rows = ceil(albumCount / c.toFloat()).toInt()
        val totalH = rows * cell + (rows - 1) * GRID_GAP_DP
        if (totalH <= heightDp) return c
        val overflow = totalH - heightDp
        if (overflow < bestOverflow) {
            bestOverflow = overflow
            best = c
        }
    }
    return best
}

/**
 * Grid de álbumes: selección por
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
    /**
     * Columnas fijas elegidas en el menú (5/6/7); null = "pantalla" (auto =
     * todos los álbumes en pantalla, sin scroll). [artVersion] fuerza la
     * recomposición cuando llega arte nuevo: Song equals solo compara path,
     * así que el mapa con portadas se ve "igual".
     */
    columnsSetting: Int? = null,
    artVersion: Int = 0,
    /** Portadas en caché ya cargadas: hasta entonces el grid no se enseña. */
    coversReady: Boolean = true,
    /** Espacio reservado bajo la última fila (p. ej. el mini player flotante). */
    bottomContentPadding: Dp = 12.dp,
    /** Álbum de la canción en curso: lleva anillo del aura y ecualizador. */
    playingAlbumKey: String? = null,
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

    // UNA transición infinita compartida por todo el grid, no una por celda:
    // en modo reordenar, N celdas visibles invalidaban su propia capa cada
    // frame. Fuera de ese modo no hay animación en absoluto (cero coste).
    val jiggle = rememberJiggle(isReordering)

    // Sin entrada animada: el grid aparece de golpe cuando las portadas en
    // caché están listas (con tope), para no enseñar celdas sin portada.
    val coversReadyState by rememberUpdatedState(coversReady)
    var showGrid by remember { mutableStateOf(coversReady) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(COVERS_MAX_WAIT_MS) { snapshotFlow { coversReadyState }.first { it } }
        showGrid = true
    }

    BoxWithConstraints(modifier.graphicsLayer { alpha = if (showGrid) 1f else 0f }) {
        val density = LocalDensity.current
        val columns = with(density) {
            val widthDp = constraints.maxWidth.toDp().value
            if (columnsSetting != null) {
                columnsSetting
            } else {
                // Alto útil: el viewport menos el padding superior del grid y
                // el hueco reservado bajo la última fila.
                val usableHeightDp =
                    constraints.maxHeight.toDp().value - 12f - bottomContentPadding.value
                fitAllColumns(order.size, widthDp, usableHeightDp)
            }
        }
        val artSize = with(density) {
            ((constraints.maxWidth.toDp().value - 2 * GRID_H_PAD_DP - (columns - 1) * GRID_GAP_DP) / columns).dp
        }
        val cellRadius = with(density) {
            val widthDp = constraints.maxWidth.toDp().value
            val cellWidthDp = (widthDp - 2 * GRID_H_PAD_DP - (columns - 1) * GRID_GAP_DP) / columns
            // Radio proporcional a la celda: con pocas columnas grandes queda
            // en el radio estándar; con "Pantalla" y celdas pequeñas evita el
            // efecto círculo de un radio fijo.
            minOf(EighthTheme.RadiusM, (cellWidthDp * 0.22f).dp)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = 12.dp,
                bottom = if (isReordering) maxOf(40.dp, bottomContentPadding) else bottomContentPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = !isReordering,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = order.size, key = { order[it] }) { index ->
                val albumName = order[index]
                val albumSongs = grouped[albumName] ?: emptyList()
                // ByteArray: Compose lo compara por identidad, así que la
                // celda SÍ se recompone cuando la portada llega más tarde.
                val coverArt = albumSongs.firstOrNull { it.albumArt != null }?.albumArt
                val isDragging = draggingKey == albumName
                val isHovered = hoveredKey == albumName && draggingKey != null && !isDragging
                if (isReordering) {
                    ReorderCell(
                        albumName = albumName,
                        coverArt = coverArt,
                        index = index,
                        jiggle = jiggle,
                        isDragging = isDragging,
                        isHovered = isHovered,
                        dragOffset = dragOffset,
                        cellRadius = cellRadius,
                        artSize = artSize,
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
                            val newOrder = order.toMutableList().apply { removeAt(from); add(to, albumName) }
                            order = newOrder
                            // Un size de 0 (celda aún sin medir) daría una
                            // división por cero: Offset(NaN) en graphicsLayer
                            // lanza en debug y desaparece en release.
                            dragOffset = if (stepX > 0f && stepY > 0f) {
                                val oldCol = dragged.offset.x / stepX
                                val oldRow = dragged.offset.y / stepY
                                Offset(
                                    dragOffset.x + (oldCol - (to % columns)) * stepX,
                                    dragOffset.y + (oldRow - (to / columns)) * stepY,
                                )
                            } else {
                                Offset.Zero
                            }
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
                        coverArt = coverArt,
                        isSelected = albumName in selectedAlbums,
                        isPlaying = albumName == playingAlbumKey,
                        cellRadius = cellRadius,
                        artSize = artSize,
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
    coverArt: ByteArray?,
    isSelected: Boolean,
    isPlaying: Boolean,
    cellRadius: Dp,
    artSize: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val aura = animatedAura()
    val art = rememberArtBitmap(coverArt, targetSize = artSize)
    val cellShape = RoundedCornerShape(cellRadius)
    val cellClipShape = RoundedCornerShape(cellRadius - 1.dp)
    val fill by animateColorAsState(
        targetValue = if (isSelected) EighthTheme.SelectedCard.copy(alpha = 0.92f)
        else EighthTheme.GlassSurface.copy(alpha = 0.54f),
        animationSpec = tween(420, easing = EighthTheme.EasingStandard),
        label = "albumCellFill",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> EighthTheme.accent(aura).copy(alpha = 0.88f)
            isPlaying -> EighthTheme.accent(aura).copy(alpha = 0.95f)
            else -> EighthTheme.GlassBorder
        },
        animationSpec = tween(420, easing = EighthTheme.EasingStandard),
        label = "albumCellBorder",
    )
    val source = remember(albumName) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "albumPress",
    )
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .shadow(
                if (isPlaying) 14.dp else 8.dp, cellShape,
                spotColor = if (isPlaying) aura.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.18f),
                ambientColor = Color.Transparent,
            )
            .background(fill, cellShape)
            .border(
                BorderStroke(if (isSelected || isPlaying) 1.5.dp else 0.7.dp, borderColor),
                cellShape,
            )
            .semantics {
                contentDescription = albumName
                selected = isSelected
            }
            .combinedClickable(
                onLongClickLabel = "Seleccionar álbum",
                interactionSource = source,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .clip(cellClipShape),
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
                AlbumNamePlaceholder(albumName)
            }
        }
        if (isPlaying && !isSelected) {
            // Insignia "sonando": ecualizador sobre una píldora oscura.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(minOf(22.dp, artSize * 0.3f))
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                EqualizerBars(aura, Modifier.fillMaxSize(0.56f))
            }
        }
        if (isSelected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(EighthTheme.accent(aura).copy(alpha = 0.58f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/**
 * Oscilación compartida para el modo reordenar.
 *
 * Devuelve 0f (sin animación, sin frames) cuando [active] es false: crearla
 * siempre hacía que el grid se recompusiera a 60fps durante toda la
 * navegación normal, que es el caso habitual.
 */
@Composable
private fun rememberJiggle(active: Boolean): Float {
    if (!active) return 0f
    val transition = rememberInfiniteTransition(label = "albumJiggle")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
        label = "jiggleAngle",
    )
    return value
}

/** Celda en modo reordenar: jiggle iOS; la celda arrastrada vuela con el dedo. */
@Composable
private fun ReorderCell(
    albumName: String,
    coverArt: ByteArray?,
    index: Int,
    jiggle: Float,
    isDragging: Boolean,
    isHovered: Boolean,
    dragOffset: Offset,
    cellRadius: Dp,
    artSize: Dp,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val aura = animatedAura()
    val cellShape = RoundedCornerShape(cellRadius)
    val cellClipShape = RoundedCornerShape(cellRadius - 1.dp)
    // pointerInput está cacheado por clave: sin esto el bloque de gesto
    // ejecutaría el primer callback recibido para siempre.
    val currentStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onDragEnd)
    // jiggle viene del grid (transición única compartida): aquí solo se
    // deriva el ángulo alterno de la celda.
    val angle = (jiggle - 0.5f) * 0.035f * (if (index % 2 == 0) 1f else -1f)
    val art = rememberArtBitmap(coverArt, targetSize = artSize)
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
                    onDragStart = { currentStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        currentDrag(amount)
                    },
                    onDragEnd = { currentEnd() },
                    onDragCancel = { currentEnd() },
                )
            },
    ) {
        val borderColor = if (isHovered && !isDragging) aura.copy(alpha = 0.8f) else EighthTheme.GlassBorder
        val borderWidth = if (isHovered && !isDragging) 1.2.dp else 0.7.dp
        // Sin fondo de relleno: si aún no hay portada la celda queda
        // transparente; el borde guía el arrastre/hover.
        Box(
            Modifier
                .fillMaxSize()
                .border(BorderStroke(borderWidth, borderColor), cellShape)
                .clip(cellClipShape),
        ) {
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = albumName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AlbumNamePlaceholder(albumName)
            }
        }
    }
}

@Composable
private fun AlbumNamePlaceholder(albumName: String) {
    Box(
        Modifier.fillMaxSize().background(EighthTheme.GlassSurfaceLight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            albumName,
            color = EighthTheme.TextPrimary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp),
        )
    }
}
