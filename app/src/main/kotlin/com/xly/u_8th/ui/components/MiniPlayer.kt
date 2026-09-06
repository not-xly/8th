package com.xly.u_8th.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.AppStrings
import kotlinx.coroutines.launch

private enum class LockedAxis { Horizontal, Vertical }
private enum class SlidePhase { Idle, Exiting, Entering }

/**
 * Mini reproductor con gestos (port of widgets/mini_player.dart): swipe
 * horizontal con peek para cambiar de tema, swipe vertical para abrir el
 * full player y barra de progreso superpuesta.
 */
@Composable
fun MiniPlayer(
    song: Song,
    nextSong: Song?,
    previousSong: Song?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTap: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Constantes del gesto (px lógicos en el port; aquí dp).
    val hMaxPx = with(density) { 96.dp.toPx() }
    val vMaxPx = with(density) { 70.dp.toPx() }
    val hThresholdPx = with(density) { 40.dp.toPx() }
    val vThresholdPx = with(density) { 34.dp.toPx() }
    val velocityThresholdPx = with(density) { 650.dp.toPx() }

    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragScale by remember { mutableFloatStateOf(1f) }
    var lockedAxis by remember { mutableStateOf<LockedAxis?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubRatio by remember { mutableStateOf<Float?>(null) }
    var slideDir by remember { mutableIntStateOf(1) }
    var phase by remember { mutableStateOf(SlidePhase.Idle) }
    var lastProgressInteractionMs by remember { mutableStateOf(0L) }

    val offsetAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val opacityAnim = remember { Animatable(1f) }

    fun cancelSpring() {
        scope.launch {
            offsetAnim.stop()
            opacityAnim.stop()
            if (phase != SlidePhase.Idle) {
                phase = SlidePhase.Idle
                opacityAnim.snapTo(1f)
                offsetAnim.snapTo(Offset.Zero)
                dragOffset = Offset.Zero
            }
        }
    }

    fun animateBack() {
        val from = dragOffset
        scope.launch {
            if (from.getDistance() < 0.5f) {
                dragOffset = Offset.Zero
                dragScale = 1f
                if (phase != SlidePhase.Idle) {
                    phase = SlidePhase.Idle
                    opacityAnim.snapTo(1f)
                }
                return@launch
            }
            offsetAnim.snapTo(from)
            offsetAnim.animateTo(Offset.Zero, tween(320, easing = EighthTheme.EasingOutBack))
            dragOffset = Offset.Zero
            dragScale = 1f
            if (phase != SlidePhase.Idle) {
                phase = SlidePhase.Idle
                opacityAnim.snapTo(1f)
            }
        }
    }

    // Slide-out hacia el borde; el cambio de tema y la entrada ocurren al completar.
    fun commitSlide(next: Boolean) {
        slideDir = if (next) 1 else -1
        phase = SlidePhase.Exiting
        lockedAxis = null
        val exitX = if (next) -hMaxPx else hMaxPx
        scope.launch {
            kotlinx.coroutines.coroutineScope {
                launch {
                    offsetAnim.snapTo(dragOffset)
                    offsetAnim.animateTo(Offset(exitX, 0f), tween(150, easing = EighthTheme.EasingInCubic))
                }
                launch { opacityAnim.animateTo(0f, tween(150, easing = EighthTheme.EasingInCubic)) }
            }
            val enterX = if (next) hMaxPx * 0.5f else -hMaxPx * 0.5f
            if (next) onNext() else onPrevious()
            phase = SlidePhase.Entering
            dragOffset = Offset.Zero
            dragScale = 1f
            offsetAnim.snapTo(Offset(enterX, 0f))
            kotlinx.coroutines.coroutineScope {
                launch {
                    offsetAnim.animateTo(Offset.Zero, tween(320, easing = EighthTheme.EasingOutBack))
                }
                launch { opacityAnim.animateTo(1f, tween(320, easing = EighthTheme.EasingOutCubic)) }
            }
            phase = SlidePhase.Idle
        }
    }

    val isDraggingH =
        (lockedAxis == LockedAxis.Horizontal && kotlin.math.abs(dragOffset.x) > 8f) ||
            (lockedAxis == null &&
                kotlin.math.abs(dragOffset.x) > 8f &&
                kotlin.math.abs(dragOffset.x) > kotlin.math.abs(dragOffset.y))
    val dragProgress = (kotlin.math.abs(dragOffset.x) / hMaxPx).coerceIn(0f, 1f)
    val isDraggingLeft = dragOffset.x < 0
    val peekSong = if (isDraggingLeft) nextSong else previousSong
    val showPeek = isDraggingH && peekSong != null && phase == SlidePhase.Idle

    val horizontalShift = if (isDraggingH) dragOffset.x else 0f
    val verticalShift = if (lockedAxis == LockedAxis.Vertical) dragOffset.y * 0.18f else 0f
    val contentScale = when {
        isDraggingH -> 1f - 0.05f * dragProgress
        lockedAxis == LockedAxis.Vertical -> dragScale
        else -> 1f
    }
    val contentOpacity = when {
        phase == SlidePhase.Exiting -> opacityAnim.value
        phase == SlidePhase.Entering -> opacityAnim.value
        isDraggingH -> (1f - dragProgress * 0.85f).coerceIn(0.15f, 1f)
        else -> 1f
    }
    val animatedOffset = if (phase == SlidePhase.Idle) dragOffset else offsetAnim.value

    val aura = animatedAura()
    val art = rememberArtBitmap(song.albumArt, sample = 2)

    Box(modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = dragScale
                    scaleY = dragScale
                    transformOrigin = TransformOrigin.Center
                }
                .shadow(24.dp, RoundedCornerShape(EighthTheme.RadiusM), spotColor = Color.Black.copy(alpha = 0.32f), ambientColor = Color.Transparent)
                .shadow(8.dp, RoundedCornerShape(EighthTheme.RadiusM), spotColor = Color.Black.copy(alpha = 0.14f), ambientColor = Color.Transparent)
                .background(EighthTheme.GlassSurface.copy(alpha = 0.72f), RoundedCornerShape(EighthTheme.RadiusM))
                .border(
                    BorderStroke(0.9.dp, EighthTheme.GlassBorderStrong),
                    RoundedCornerShape(EighthTheme.RadiusM),
                )
                .clip(RoundedCornerShape(EighthTheme.RadiusM - 1.dp))
                // Tap fuera de la barra abre el full player; AquaSlider
                // consume sus propios toques y drags, así no hay doble gesto.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val now = System.currentTimeMillis()
                            if (now - lastProgressInteractionMs < 300) return@detectTapGestures
                            onTap()
                        },
                    )
                }
                .pointerInput(hMaxPx, vMaxPx, velocityThresholdPx) {
                    // Velocidad estimada a mano: la sobrecarga de
                    // detectDragGestures con change final no existe aquí.
                    var lastTimeMs = 0L
                    var velX = 0f
                    var velY = 0f
                    detectDragGestures(
                        onDragStart = {
                            if (scrubbing) return@detectDragGestures
                            lastTimeMs = 0L
                            velX = 0f
                            velY = 0f
                            cancelSpring()
                            lockedAxis = null
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            if (scrubbing) return@detectDragGestures
                            val now = change.uptimeMillis
                            if (lastTimeMs != 0L) {
                                val dt = (now - lastTimeMs) / 1000f
                                if (dt > 0f) {
                                    velX = 0.7f * velX + 0.3f * (delta.x / dt)
                                    velY = 0.7f * velY + 0.3f * (delta.y / dt)
                                }
                            }
                            lastTimeMs = now
                            if (lockedAxis == null) {
                                val tentativeX = dragOffset.x + delta.x
                                val tentativeY = dragOffset.y + delta.y
                                if (kotlin.math.abs(tentativeX) > 6 || kotlin.math.abs(tentativeY) > 6) {
                                    lockedAxis =
                                        if (kotlin.math.abs(tentativeX) > kotlin.math.abs(tentativeY)) {
                                            LockedAxis.Horizontal
                                        } else {
                                            LockedAxis.Vertical
                                        }
                                }
                            }
                            var dx = dragOffset.x
                            var dy = dragOffset.y
                            when (lockedAxis) {
                                LockedAxis.Horizontal -> {
                                    // Resistencia progresiva: cuanto más lejos, más cuesta.
                                    val raw = dx + delta.x
                                    val sign = if (raw < 0) -1f else 1f
                                    val absRaw = kotlin.math.abs(raw)
                                    dx = sign * hMaxPx * (1f - 1f / (absRaw / (hMaxPx * 0.45f) + 1f))
                                    dy = 0f
                                    dragScale = 1f
                                }
                                LockedAxis.Vertical -> {
                                    dx = 0f
                                    dy = (dy + delta.y).coerceIn(-vMaxPx, 20f)
                                    dragScale = if (dy < 0) (1f + (-dy / 420f)).coerceIn(1f, 1.08f) else 1f
                                }
                                null -> {
                                    dx = (dx + delta.x).coerceIn(-hMaxPx, hMaxPx)
                                    dy = (dy + delta.y).coerceIn(-vMaxPx, 20f)
                                    dragScale = if (dy < 0) (1f + (-dy / 420f)).coerceIn(1f, 1.08f) else 1f
                                }
                            }
                            dragOffset = Offset(dx, dy)
                        },
                        onDragEnd = {
                            if (scrubbing) {
                                scrubbing = false
                                return@detectDragGestures
                            }
                            val v = Offset(velX, velY)
                            val dx = dragOffset.x
                            val dy = dragOffset.y
                            val isHorizontal = lockedAxis == LockedAxis.Horizontal ||
                                (lockedAxis == null && kotlin.math.abs(dx) > kotlin.math.abs(dy))
                            if (isHorizontal) {
                                val next = dx < -hThresholdPx || v.x < -velocityThresholdPx
                                val prev = dx > hThresholdPx || v.x > velocityThresholdPx
                                if (next || prev) {
                                    dragScale = 1f
                                    commitSlide(next)
                                } else {
                                    animateBack()
                                    dragScale = 1f
                                    lockedAxis = null
                                }
                            } else {
                                lockedAxis = null
                                if (dy < -vThresholdPx || v.y < -velocityThresholdPx) {
                                    dragOffset = Offset.Zero
                                    dragScale = 1f
                                    onTap()
                                } else {
                                    animateBack()
                                    dragScale = 1f
                                }
                            }
                        },
                        onDragCancel = {
                            if (scrubbing) {
                                scrubbing = false
                            } else {
                                animateBack()
                                dragScale = 1f
                                lockedAxis = null
                            }
                        },
                    )
                },
        ) {
            Box(Modifier.height(60.dp)) {
                // Peek del siguiente/anterior: misma geometría, solo funde.
                if (showPeek) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = dragProgress }
                            .padding(start = 10.dp, end = 8.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PeekArt(peekSong!!)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isDraggingLeft) "A continuación" else "Anterior",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp,
                                color = aura,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                peekSong!!.displayTitle,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                letterSpacing = (-0.2).sp,
                                color = EighthTheme.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            if (isDraggingLeft) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious,
                            null,
                            tint = aura,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                // Contenido actual
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = if (phase == SlidePhase.Idle) horizontalShift else animatedOffset.x
                            translationY = if (phase == SlidePhase.Idle) verticalShift else animatedOffset.y
                            scaleX = contentScale
                            scaleY = contentScale
                            alpha = contentOpacity
                        },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Fila principal (levemente subida para dejar hueco a la barra)
                        Row(
                            Modifier
                                .weight(1f)
                                .padding(start = 10.dp, end = 8.dp)
                                .graphicsLayer { translationY = -3.dp.toPx() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (art != null) {
                                Image(
                                    bitmap = art,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(9.dp)),
                                )
                            } else {
                                ArtPlaceholder(
                                    modifier = Modifier.size(40.dp),
                                    radius = 9.dp,
                                    icon = Icons.Rounded.MusicNote,
                                    iconTint = EighthTheme.TextSecondary,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (phase == SlidePhase.Entering) {
                                    TitleColumn(song)
                                } else {
                                    AnimatedContent(
                                        targetState = song,
                                        transitionSpec = {
                                            val dir = slideDir.toFloat()
                                            (
                                                slideInHorizontally(tween(260, easing = EighthTheme.EasingOutCubic)) { it -> (dir * 0.2f * it).toInt() } +
                                                    fadeIn(tween(260, easing = EighthTheme.EasingOutCubic))
                                                ).togetherWith(
                                                slideOutHorizontally(tween(260, easing = EighthTheme.EasingInCubic)) { it -> (-dir * 0.2f * it).toInt() } +
                                                    fadeOut(tween(260, easing = EighthTheme.EasingInCubic))
                                            )
                                        },
                                        label = "miniTrack",
                                    ) { s ->
                                        TitleColumn(s)
                                    }
                                }
                            }
                            MiniIconButton(Icons.Rounded.SkipPrevious, "Anterior", onPrevious)
                            Box(
                                Modifier
                                    .padding(2.dp)
                                    .size(36.dp)
                                    .shadow(12.dp, RoundedCornerShape(10.dp), spotColor = aura.copy(alpha = 0.32f), ambientColor = Color.Transparent)
                                    .background(aura, RoundedCornerShape(10.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onTogglePlay,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playing) "Pausa" else "Reproducir",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            MiniIconButton(Icons.Rounded.SkipNext, "Siguiente", onNext)
                            Spacer(Modifier.width(2.dp))
                        }
                    }
                    // Barra de progreso superpuesta al borde inferior
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        val progress = scrubRatio?.takeIf { scrubbing }
                            ?: (if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f)
                        AquaSlider(
                            progress = progress,
                            onSeek = { fraction ->
                                lastProgressInteractionMs = System.currentTimeMillis()
                                if (durationMs > 0 && !scrubbing) {
                                    onSeek((durationMs * fraction).toLong())
                                }
                                if (scrubbing) scrubRatio = fraction
                            },
                            scrubbing = scrubbing,
                            onScrubStart = {
                                lastProgressInteractionMs = System.currentTimeMillis()
                                scrubbing = true
                                scrubRatio = null
                                cancelSpring()
                                lockedAxis = null
                                dragOffset = Offset.Zero
                                dragScale = 1f
                            },
                            onScrubEnd = { fraction ->
                                if (durationMs > 0) onSeek((durationMs * fraction).toLong())
                                scrubbing = false
                                scrubRatio = null
                            },
                            touchHeight = 14.dp,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeekArt(peekSong: Song) {
    val art: ImageBitmap? = rememberArtBitmap(peekSong.albumArt, sample = 2)
    if (art != null) {
        Image(
            bitmap = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(9.dp)),
        )
    } else {
        ArtPlaceholder(
            modifier = Modifier.size(40.dp),
            radius = 9.dp,
            icon = Icons.Rounded.MusicNote,
            iconTint = EighthTheme.TextSecondary,
        )
    }
}

@Composable
private fun TitleColumn(song: Song) {
    Column {
        Text(
            song.displayTitle,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
            letterSpacing = (-0.2).sp,
            color = EighthTheme.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            song.artist ?: song.album ?: AppStrings.UNKNOWN,
            color = EighthTheme.TextSecondary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MiniIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(30.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = EighthTheme.TextPrimary, modifier = Modifier.size(20.dp))
    }
}
