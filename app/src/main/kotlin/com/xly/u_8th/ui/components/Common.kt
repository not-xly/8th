package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Glass surface modifier (translucent fill + hairline border + soft shadow;
// like the original, cards never blur — only fixed chrome blurred, and the
// near-solid dark background makes the translucent fill visually equivalent).
// ---------------------------------------------------------------------------

fun Modifier.glassSurface(
    radius: Dp = EighthTheme.RadiusM,
    opacity: Float = 0.62f,
    borderColor: Color = EighthTheme.GlassBorder,
    borderWidth: Dp = 0.7.dp,
    shadowElevation: Dp = 0.dp,
    color: Color = EighthTheme.GlassSurface,
): Modifier = this
    .then(if (shadowElevation > 0.dp) Modifier.shadow(shadowElevation, RoundedCornerShape(radius)) else Modifier)
    .background(color.copy(alpha = opacity), RoundedCornerShape(radius))
    .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(radius))

/** Selected/playing card: warm tinted fill with an aura border. */
fun Modifier.glassCardActive(
    aura: Color,
    radius: Dp = EighthTheme.RadiusM,
): Modifier = this
    .shadow(10.dp, RoundedCornerShape(radius))
    .background(EighthTheme.SelectedCard.copy(alpha = 0.92f), RoundedCornerShape(radius))
    .border(BorderStroke(1.dp, aura.copy(alpha = 0.88f)), RoundedCornerShape(radius))

// ---------------------------------------------------------------------------
// 36x36 glass icon button used across header, tiles and app bars.
// ---------------------------------------------------------------------------

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    active: Boolean = false,
    enabled: Boolean = true,
    activeTint: Color? = null,
    tint: Color = EighthTheme.TextSecondary,
    borderColor: Color = EighthTheme.GlassBorder,
    radius: Dp = EighthTheme.RadiusS,
) {
    val aura = animatedAura()
    val fill = when {
        !enabled -> Color.White.copy(alpha = 0.03f)
        active -> (activeTint ?: aura).copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val border = when {
        !enabled -> Color.Transparent
        active -> (activeTint ?: aura).copy(alpha = 0.28f)
        else -> borderColor
    }
    val iconColor = when {
        !enabled -> EighthTheme.TextTertiary
        active -> (activeTint ?: aura)
        else -> tint
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(fill)
            .border(BorderStroke(0.7.dp, border), RoundedCornerShape(radius))
            .clickable(
                interactionSource = MutableInteractionSource(),
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

// ---------------------------------------------------------------------------
// Custom seek bar: 3.5dp track, aura played portion with glow, white thumb.
// Tap to seek; drag to scrub.
// ---------------------------------------------------------------------------

@Composable
fun AquaSlider(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    scrubbing: Boolean = false,
    onScrubStart: () -> Unit = {},
    onScrubEnd: (Float) -> Unit = {},
    activeColor: Color? = null,
    touchHeight: Dp = 24.dp,
) {
    val aura = animatedAura()
    val active = activeColor ?: aura
    var width by androidx.compose.runtime.remember { mutableStateOf(0f) }
    val shape = RoundedCornerShape(2.dp)

    fun fractionAt(x: Float): Float = (if (width > 0f) x / width else 0f).coerceIn(0f, 1f)

    // Latest fraction seen this gesture: onScrubEnd gets the live value,
    // not the `progress` captured when the callback was created.
    var latestFraction by androidx.compose.runtime.remember { mutableStateOf(progress) }

    fun seekTo(fraction: Float) {
        latestFraction = fraction
        onSeek(fraction)
    }

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset -> seekTo(fractionAt(offset.x)) },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onScrubStart()
                        seekTo(fractionAt(offset.x))
                    },
                    onDragEnd = { onScrubEnd(latestFraction) },
                    onDragCancel = { onScrubEnd(latestFraction) },
                ) { change, _ ->
                    change.consume()
                    seekTo(fractionAt(change.position.x))
                }
            }
            .heightIn(min = touchHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(3.5.dp)
                .clip(shape),
        ) {
            val y = size.height / 2f
            val stroke = 3.5.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = if (scrubbing) 0.18f else 0.12f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            val played = size.width * progress.coerceIn(0f, 1f)
            if (played > 0f) {
                // Aura glow approximated with a translucent halo stroke.
                drawLine(
                    color = active.copy(alpha = 0.18f),
                    start = Offset(0f, y),
                    end = Offset(played, y),
                    strokeWidth = stroke * 4f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = active,
                    start = Offset(0f, y),
                    end = Offset(played, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            if (played > 0f || scrubbing) {
                drawCircle(color = Color.White, radius = 7.dp.toPx(), center = Offset(played, y))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Glass notification (custom toast): icon chip + message + close glyph,
// slide-up + fade entrance, auto-hide, tap to dismiss.
// ---------------------------------------------------------------------------

class NotificationState {
    var message by mutableStateOf<String?>(null)
        private set
    var icon by mutableStateOf<ImageVector?>(null)
        private set
    var iconTint by mutableStateOf<Color?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(Dispatchers.Main)
    private val seq = mutableIntStateOf(0)

    fun show(text: String, icon: ImageVector, tint: Color? = null, durationMs: Long = 2000) {
        seq.intValue++
        message = text
        this.icon = icon
        this.iconTint = tint
        visible = true
        val mySeq = seq.intValue
        scope.launch {
            delay(durationMs)
            if (seq.intValue == mySeq) visible = false
        }
    }

    fun dismiss() {
        visible = false
    }
}

@Composable
fun GlassNotification(
    state: NotificationState,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = state.visible && state.message != null,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(320, easing = EighthTheme.EasingOutCubic),
        ) + androidx.compose.animation.slideInVertically(
            animationSpec = androidx.compose.animation.core.tween(320, easing = EighthTheme.EasingOutCubic),
            initialOffsetY = { it / 8 },
        ),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = bottomPadding)
                .fillMaxWidth()
                .clip(RoundedCornerShape(EighthTheme.RadiusL))
                .background(EighthTheme.GlassSurface.copy(alpha = 0.78f))
                .border(
                    BorderStroke(0.8.dp, EighthTheme.GlassBorderStrong),
                    RoundedCornerShape(EighthTheme.RadiusL),
                )
                .clickable(
                    interactionSource = MutableInteractionSource(),
                    indication = null,
                ) { state.dismiss() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tint = state.iconTint ?: EighthTheme.TextSecondary
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.14f))
                        .border(BorderStroke(0.7.dp, tint.copy(alpha = 0.22f)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        state.icon ?: Icons.Rounded.Close,
                        null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    state.message ?: "",
                    color = EighthTheme.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Close, null, tint = EighthTheme.TextTertiary, modifier = Modifier.size(14.dp))
            }
        }
    }
}
