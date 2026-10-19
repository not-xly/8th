package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.unit.sp
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.ui.theme.lerpColor
import kotlinx.coroutines.CoroutineScope
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

/**
 * El cristal común de la app: degradado con brillo arriba y filo que se
 * apaga hacia abajo. Con [accent] se tiñe (estado activo).
 */
fun Modifier.glass(
    shape: Shape = CircleShape,
    accent: Color? = null,
): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(EighthTheme.GlassFillTop, EighthTheme.GlassFillBottom)))
    .then(if (accent != null) Modifier.background(accent.copy(alpha = 0.18f)) else Modifier)
    .border(
        BorderStroke(
            EighthTheme.GlassStrokeWidth,
            if (accent != null) {
                SolidColor(accent.copy(alpha = 0.38f))
            } else {
                Brush.verticalGradient(listOf(EighthTheme.GlassStrokeTop, EighthTheme.GlassStrokeBottom))
            },
        ),
        shape,
    )

/**
 * Tarjeta/fila de lista común (canciones, cola, playlists, álbumes). La
 * activa (sonando, seleccionada) se tiñe con el acento y gana un filo.
 */
fun Modifier.glassCard(
    active: Boolean = false,
    accent: Color = EighthTheme.Primary,
    shape: Shape = RoundedCornerShape(EighthTheme.RadiusM),
): Modifier = this
    .clip(shape)
    .background(EighthTheme.GlassSurface.copy(alpha = 0.88f))
    .then(if (active) Modifier.background(accent.copy(alpha = 0.14f)) else Modifier)
    .border(
        if (active) BorderStroke(1.dp, accent.copy(alpha = 0.6f)) else BorderStroke(0.7.dp, EighthTheme.GlassBorder),
        shape,
    )

/** Superficie opaca de diálogos y tarjetas flotantes. */
fun Modifier.glassPopup(shape: Shape = RoundedCornerShape(EighthTheme.RadiusXL)): Modifier = this
    .shadow(24.dp, shape, spotColor = Color.Black.copy(alpha = 0.5f), ambientColor = Color.Transparent)
    .clip(shape)
    .background(EighthTheme.PopupSurface)
    .border(BorderStroke(EighthTheme.GlassStrokeWidth, EighthTheme.GlassBorderStrong), shape)

/** Estilo único para los menús desplegables. */
object GlassMenu {
    val shape = RoundedCornerShape(EighthTheme.RadiusL)
    val color = EighthTheme.PopupSurface
    val border = BorderStroke(EighthTheme.GlassStrokeWidth, EighthTheme.GlassBorderStrong)
}

/**
 * Menú desplegable de la app (botones de 3 puntos): superficie opaca con
 * esquinas grandes y filas propias ([GlassMenuItem]) en vez de las de
 * Material, que traían ripple y tipografía de otro estilo.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 230.dp).padding(horizontal = 6.dp),
        offset = DpOffset(0.dp, 8.dp),
        shape = GlassMenu.shape,
        containerColor = GlassMenu.color,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        border = GlassMenu.border,
        content = content,
    )
}

/** Fila de [GlassDropdownMenu]: icono en un círculo suave y texto. */
@Composable
fun GlassMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = when {
        !enabled -> EighthTheme.TextTertiary
        destructive -> EighthTheme.Danger
        else -> EighthTheme.TextPrimary
    }
    val bg by animateColorAsState(if (pressed) EighthTheme.GlassPressed else Color.Transparent, tween(120), label = "menuItem")
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(EighthTheme.RadiusM))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(fg.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Botón de texto en cápsula. [primary]: relleno de acento (o [color], por
 * ejemplo rojo para borrar). Si no, cristal como el resto de controles.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    color: Color? = null,
) {
    val fill = color ?: EighthTheme.accent(animatedAura())
    val fg = if (primary) Color.White else EighthTheme.TextPrimary
    Row(
        modifier
            .pressClickable(enabled = enabled, pressedScale = 0.95f, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                if (primary) {
                    Modifier
                        .shadow(12.dp, CircleShape, spotColor = fill.copy(alpha = 0.35f), ambientColor = Color.Transparent)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(lerpColor(fill, Color.Black, 0.12f), lerpColor(fill, Color.Black, 0.32f)),
                            ),
                        )
                        .border(BorderStroke(EighthTheme.GlassStrokeWidth, Color.White.copy(alpha = 0.22f)), CircleShape)
                } else {
                    Modifier.glass()
                },
            )
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Selected/playing card: warm tinted fill with an aura border. */
fun Modifier.glassCardActive(
    aura: Color,
    radius: Dp = EighthTheme.RadiusM,
): Modifier = this
    .shadow(10.dp, RoundedCornerShape(radius))
    .background(EighthTheme.SelectedCard.copy(alpha = 0.92f), RoundedCornerShape(radius))
    .border(BorderStroke(1.dp, aura.copy(alpha = 0.88f)), RoundedCornerShape(radius))

// ---------------------------------------------------------------------------
// Press feedback: the app draws no ripples (glass look), so tappable chrome
// shrinks slightly on press and springs back instead.
// ---------------------------------------------------------------------------

/**
 * [clickable] sin ripple con un leve encogimiento elástico al pulsar.
 * [pressedScale] 1f desactiva la escala (filas largas donde molestaría).
 */
@Composable
fun Modifier.pressClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.9f,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return this
        .scale(scale)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

// ---------------------------------------------------------------------------
// Botón de icono redondo de cristal, el mismo en toda la app.
// ---------------------------------------------------------------------------

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = EighthTheme.IconButtonS,
    iconSize: Dp = size * 0.5f,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color = EighthTheme.TextPrimary.copy(alpha = 0.9f),
) {
    val accent = EighthTheme.accent(animatedAura())
    val iconColor by animateColorAsState(
        when {
            !enabled -> EighthTheme.TextTertiary
            active -> accent
            else -> tint
        },
        tween(220), label = "glassIcon",
    )
    Box(
        modifier = modifier
            .size(size)
            .pressClickable(enabled = enabled, pressedScale = 0.86f, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
            .glass(accent = if (active) accent else null),
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
    /**
     * Recibe la fracción (0..1) del punto tocado: quien lo use puede fijar
     * ahí el valor inicial del arrastre en vez de esperar al primer
     * [onSeek] (si no, la barra vuelve a pintar la posición viva del
     * reproductor y salta hacia atrás en cada agarre).
     */
    onScrubStart: (fraction: Float) -> Unit = {},
    onScrubEnd: (Float) -> Unit = {},
    activeColor: Color? = null,
    touchHeight: Dp = 48.dp,
    compact: Boolean = false,
    gestureKey: Any? = Unit,
    onScrubCancel: () -> Unit = {},
) {
    val currentSeek by rememberUpdatedState(onSeek)
    val currentStart by rememberUpdatedState(onScrubStart)
    val currentEnd by rememberUpdatedState(onScrubEnd)
    val currentCancel by rememberUpdatedState(onScrubCancel)
    var width by remember { mutableFloatStateOf(0f) }

    fun fractionAt(x: Float): Float = (if (width > 0f) x / width else 0f).coerceIn(0f, 1f)

    // Latest fraction seen this gesture: onScrubEnd gets the live value,
    // not the `progress` captured when the callback was created. No hace
    // falta indexar por `progress`: `seekTo` ya lo fija en cada gesto y en el
    // arranque, así que una clave por progreso solo generaba estado nuevo en
    // cada tick de posición del reproductor.
    var latestFraction by remember { mutableFloatStateOf(progress) }

    fun seekTo(fraction: Float) {
        latestFraction = fraction
        currentSeek(fraction)
    }

    Box(
        modifier = modifier
            .semantics {
                contentDescription = "Posición de reproducción"
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                setProgress { value ->
                    if (!value.isFinite()) false else {
                        val target = value.coerceIn(0f, 1f)
                        currentStart(target)
                        seekTo(target)
                        currentEnd(latestFraction)
                        true
                    }
                }
            }
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(gestureKey) {
                detectTapGestures(
                    onTap = { offset -> seekTo(fractionAt(offset.x)) },
                )
            }
            .pointerInput(gestureKey) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val fraction = fractionAt(offset.x)
                        currentStart(fraction)
                        seekTo(fraction)
                    },
                    onDragEnd = { currentEnd(latestFraction) },
                    onDragCancel = { currentCancel() },
                ) { change, _ ->
                    change.consume()
                    seekTo(fractionAt(change.position.x))
                }
            }
            .heightIn(min = touchHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Pista redondeada sin pulgar: al agarrarla engorda con un muelle
        // y el tramo reproducido se ilumina (estilo reproductores actuales).
        val thickness by animateDpAsState(
            when {
                compact -> 3.dp
                scrubbing -> 12.dp
                else -> 6.dp
            },
            spring(dampingRatio = 0.7f, stiffness = 600f),
            label = "sliderThickness",
        )
        val playedColor by animateColorAsState(
            if (scrubbing) Color.White else (activeColor ?: Color.White.copy(alpha = 0.85f)),
            tween(160),
            label = "sliderPlayed",
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(thickness),
        ) {
            val r = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = if (scrubbing) 0.22f else 0.14f),
                size = size,
                cornerRadius = r,
            )
            val played = size.width * progress.coerceIn(0f, 1f)
            if (played > 0f) {
                clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r)) }) {
                    drawRect(color = playedColor, size = Size(played, size.height))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Glass notification (custom toast): icon chip + message + close glyph,
// slide-up + fade entrance, auto-hide, tap to dismiss.
// ---------------------------------------------------------------------------

/**
 * @param scope scope de la composición propietaria: los `delay` de auto-ocultado
 *  deben morir con ella. Un scope propio sin `Job` nunca se cancelaba, así que
 *  cada navegación al reproductor dejaba coroutines vivas sujetando este
 *  objeto y sus `MutableState` hasta que expiraban.
 */
class NotificationState(private val scope: CoroutineScope) {
    var message by mutableStateOf<String?>(null)
        private set
    var icon by mutableStateOf<ImageVector?>(null)
        private set
    var iconTint by mutableStateOf<Color?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

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
                .glassPopup(RoundedCornerShape(EighthTheme.RadiusL))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
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
