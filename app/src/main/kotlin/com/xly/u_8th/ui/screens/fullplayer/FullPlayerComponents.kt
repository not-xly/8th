package com.xly.u_8th.ui.screens.fullplayer

import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.playback.PlayerConnection
import com.xly.u_8th.ui.components.AquaSlider
import com.xly.u_8th.ui.components.rememberArtBitmap
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.util.fmtMs
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Shared building blocks for the full player and full lyrics screens
 * (glass 56dp app bar, gradient backdrop, album art decoder and the
 * velocity-based pan detector that drives skip/open/close flings).
 */

/**
 * Fondo de las pantallas del reproductor: degradado oscuro y, si hay
 * carátula, la propia carátula ampliada y desenfocada con un halo del aura
 * encima (fundido al cambiar de canción). Se decodifica a 48dp a propósito:
 * al ampliarla el filtrado bilineal ya la difumina, así que en API < 31
 * (sin `Modifier.blur`) el efecto sigue siendo aceptable y es barato.
 */
@Composable
fun GradientBackdrop(
    modifier: Modifier = Modifier,
    art: ByteArray? = null,
    aura: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // El Brush es constante: reconstruirlo en cada recomposición invalidaba
    // el nodo de fondo aunque los colores no hubieran cambiado.
    val backdrop = remember {
        Brush.verticalGradient(
            listOf(EighthTheme.BackgroundGradientTop, EighthTheme.BackgroundGradientBottom),
        )
    }
    val veil = remember {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.35f),
            0.45f to Color.Black.copy(alpha = 0.55f),
            1f to EighthTheme.Background.copy(alpha = 0.96f),
        )
    }
    Box(
        modifier
            .fillMaxSize()
            .background(backdrop),
    ) {
        val bitmap = rememberArtBitmap(art, targetSize = 48.dp)
        Crossfade(targetState = bitmap, animationSpec = tween(700), label = "backdropArt") { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.35f
                            scaleY = 1.35f
                            alpha = 0.75f
                        }
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier),
                )
            }
        }
        if (aura != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(aura.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.3f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(veil))
        content()
    }
}

/**
 * 56dp app bar: leading action, centered title, optional trailing action.
 */
@Composable
fun PlayerTopBar(
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Transparente: el fondo con carátula se ve detrás, como en el resto
    // de reproductores modernos; ya no hace falta el hairline.
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { title() }
        if (trailing != null) trailing() else Spacer(Modifier.width(36.dp))
    }
}

/**
 * Barra de progreso + tiempos, compartida por el reproductor y las letras.
 * Función propia (no `Column` inline) a propósito: leer `positionMs` aquí
 * limita la recomposición del tick de 500 ms a este bloque; dentro del
 * contenido de la pantalla recomponía también la carátula y los controles.
 */
@Composable
fun SeekRow(
    gestureKey: String?,
    connection: PlayerConnection,
    timeFontSize: TextUnit,
    modifier: Modifier = Modifier,
    timeFontWeight: FontWeight? = null,
) {
    Column(modifier) {
        val positionMs by PlayerBus.positionMs.collectAsStateWithLifecycle()
        val durationMs by PlayerBus.durationMs.collectAsStateWithLifecycle()
        var scrub by remember(gestureKey) { mutableStateOf<Float?>(null) }
        var scrubbing by remember(gestureKey) { mutableStateOf(false) }
        val progress = scrub
            ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        AquaSlider(
            progress = progress,
            onSeek = { v ->
                if (scrubbing) {
                    scrub = v
                } else {
                    val d = PlayerBus.durationMs.value
                    if (d > 0) connection.launch { seekTo((v * d).roundToLong()) }
                }
            },
            scrubbing = scrubbing,
            // Se fija el valor desde el toque: sin esto la barra pinta la
            // posición viva hasta el primer onSeek y salta hacia atrás.
            onScrubStart = { fraction ->
                scrubbing = true
                scrub = fraction
            },
            gestureKey = gestureKey,
            onScrubCancel = { scrubbing = false; scrub = null },
            onScrubEnd = { v ->
                scrubbing = false
                scrub = null
                val d = PlayerBus.durationMs.value
                if (d > 0) connection.launch { seekTo((v * d).roundToLong()) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val shownPos = scrub?.let { (it * durationMs).roundToLong() } ?: positionMs
            val style = TextStyle(fontFeatureSettings = "tnum")
            Text(fmtMs(shownPos), fontSize = timeFontSize, fontWeight = timeFontWeight, color = EighthTheme.TextSecondary, style = style)
            Text(fmtMs(durationMs), fontSize = timeFontSize, fontWeight = timeFontWeight, color = EighthTheme.TextSecondary, style = style)
        }
    }
}

/**
 * Estado del "arrastrar para cerrar" de las capas del reproductor: la
 * pantalla sigue al dedo hacia abajo y, al soltar, o se cierra (fling o
 * más de un cuarto de altura) o vuelve a su sitio con un muelle.
 */
class DragDismissState internal constructor(
    private val scope: CoroutineScope,
    private val nav: NavController,
) {
    internal val offset = Animatable(0f)
    internal var height by mutableFloatStateOf(1f)

    fun drag(dy: Float) {
        if (dy > 0f) nav.revealingUnderlay = true
        scope.launch { offset.snapTo(dy.coerceAtLeast(0f)) }
    }

    /**
     * true si se ha decidido cerrar. La capa termina de caer con la
     * velocidad del gesto y solo entonces se invoca [dismiss]: la salida de
     * la navegación (tween que arranca lento) frenaba en seco el fling.
     */
    fun release(vy: Float, dismiss: () -> Unit): Boolean {
        if (offset.value > 0f && (vy > 700f || offset.value > height * 0.25f)) {
            scope.launch {
                offset.animateTo(height, spring(dampingRatio = 1f, stiffness = 420f), vy.coerceAtLeast(0f))
                nav.revealingUnderlay = false
                dismiss()
            }
            return true
        }
        settle()
        return false
    }

    fun settle() {
        if (offset.value == 0f) {
            nav.revealingUnderlay = false
            return
        }
        scope.launch {
            offset.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
            nav.revealingUnderlay = false
        }
    }
}

@Composable
fun rememberDragDismissState(nav: NavController): DragDismissState {
    val scope = rememberCoroutineScope()
    return remember(scope, nav) { DragDismissState(scope, nav) }
}

/** Aplica el desplazamiento del arrastre, con esquinas que se redondean. */
fun Modifier.dragDismissLayer(state: DragDismissState): Modifier = this
    .onSizeChanged { state.height = it.height.toFloat().coerceAtLeast(1f) }
    .graphicsLayer {
        val y = state.offset.value
        val t = (y / (state.height * 0.25f)).coerceIn(0f, 1f)
        translationY = y
        val sc = 1f - 0.04f * t
        scaleX = sc
        scaleY = sc
        shape = RoundedCornerShape((28 * t).dp)
        clip = y > 0f
    }

/** Player-scoped connection looked up once per composition site. */
@Composable
fun rememberPlayerConnection(): PlayerConnection {
    val context = LocalContext.current
    return remember(context) { EighthApplication.get(context).playerConnection }
}

/**
 * Pan detector with a velocity decision (skip/open/close on release).
 * Changes consumed by children (slider, scrollables) cancel the
 * gesture so scrubbing/scrolling never triggers an action. [onEnd] receives
 * px/s velocities and the total drag distance in px.
 */
@Composable
fun Modifier.playerPanDetector(
    onVerticalDrag: ((dy: Float) -> Unit)? = null,
    onHorizontalDrag: ((dx: Float) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onEnd: (vx: Float, vy: Float, totalDx: Float, totalDy: Float) -> Unit,
): Modifier {
    // @Composable (no `composed {}`): rememberUpdatedState se aplica al
    // componer el modifier, sin la indirección que `composed` añade en
    // cada aplicación.
    val currentEnd = rememberUpdatedState(onEnd)
    val currentDrag = rememberUpdatedState(onVerticalDrag)
    val currentHDrag = rememberUpdatedState(onHorizontalDrag)
    val currentCancel = rememberUpdatedState(onCancel)
    // Coordenadas de la capa: el arrastre la mueve (translationY), así que
    // las posiciones locales del dedo se desplazan con ella. Medir en local
    // realimentaba el gesto (la capa iba a medias, temblaba y se veía
    // doble al arrastrar despacio); se mide en coordenadas de raíz.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onPlaced { coords[0] = it }.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        fun PointerInputChange.rootPosition(): Offset =
            coords[0]?.takeIf { it.isAttached }?.localToRoot(position) ?: position
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downRoot = down.rootPosition()
            val tracker = VelocityTracker()
            var childConsumed = false
            // Eje bloqueado tras superar el slop: 0 = sin decidir,
            // 1 = horizontal, 2 = vertical; ambos siguen al dedo.
            var axis = 0
            var totalDx = 0f
            var totalDy = 0f
            var lastX = downRoot.x
            var lastY = downRoot.y
            while (true) {
                val event = awaitPointerEvent()
                if (!childConsumed && event.changes.any { it.isConsumed }) {
                    childConsumed = true
                    currentCancel.value?.invoke()
                }
                val main = event.changes.firstOrNull { it.id == down.id }
                if (main != null) {
                    val p = main.rootPosition()
                    tracker.addPosition(main.uptimeMillis, p)
                    totalDx += p.x - lastX
                    totalDy += p.y - lastY
                    lastX = p.x
                    lastY = p.y
                    if (axis == 0 && (abs(totalDx) > slop || abs(totalDy) > slop)) {
                        axis = if (abs(totalDy) > abs(totalDx)) 2 else 1
                    }
                    if (!childConsumed && axis != 0) {
                        if (axis == 2) currentDrag.value?.invoke(totalDy)
                        if (axis == 1) currentHDrag.value?.invoke(totalDx)
                        // Arrastre propio: se consume para que los taps de
                        // la tarjeta/botones no se disparen al soltar. Los
                        // hijos ya vieron este evento (pase Main, hijo antes).
                        main.consume()
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
            if (childConsumed) return@awaitEachGesture
            val v = tracker.calculateVelocity()
            currentEnd.value(v.x, v.y, totalDx, totalDy)
        }
    }
}
