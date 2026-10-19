package com.xly.u_8th.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.xly.u_8th.ui.theme.EighthTheme

/** Top-level screens. Sheets/dialogs are state within MainScreen. */
sealed interface AppScreen {
    data object Main : AppScreen
    data object FullPlayer : AppScreen
    data object Lyrics : AppScreen
}

/** Tiny custom backstack; [AppNavHost] renders it as stacked layers. */
class NavController {
    val stack = mutableStateListOf<AppScreen>(AppScreen.Main)

    val current: AppScreen get() = stack.last()

    /**
     * true mientras una capa se arrastra para cerrarla: la pantalla de
     * debajo debe dibujarse aunque esté "cubierta".
     */
    var revealingUnderlay by mutableStateOf(false)

    fun push(screen: AppScreen) {
        val existing = stack.indexOf(screen)
        if (existing < 0) stack.add(screen)
        else while (stack.lastIndex > existing) stack.removeAt(stack.lastIndex)
    }

    companion object {
        val Saver = listSaver<NavController, String>(
            save = { nav -> nav.stack.map { it.saveKey } },
            restore = { keys ->
                NavController().apply {
                    stack.clear()
                    stack.addAll(keys.mapNotNull { key ->
                        when (key) {
                            "main" -> AppScreen.Main
                            "player" -> AppScreen.FullPlayer
                            "lyrics" -> AppScreen.Lyrics
                            else -> null
                        }
                    })
                    if (stack.firstOrNull() != AppScreen.Main) stack.add(0, AppScreen.Main)
                }
            },
        )
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

private val AppScreen.saveKey: String
    get() = when (this) {
        AppScreen.Main -> "main"
        AppScreen.FullPlayer -> "player"
        AppScreen.Lyrics -> "lyrics"
    }

/** Entrada de las capas (reproductor, letras): sube desde abajo. */
private val layerEnter =
    slideInVertically(tween(420, easing = EighthTheme.EasingOutCubic)) { it } +
        fadeIn(tween(200))

/** Salida: cae hacia abajo, algo más rápida que la entrada. */
private val layerExit =
    slideOutVertically(tween(300, easing = EighthTheme.EasingInCubic)) { it } +
        fadeOut(tween(260, delayMillis = 60))

/**
 * Pila como capas superpuestas: Main se compone siempre (conserva scroll,
 * pestañas y estado sin re-crearse al cerrar el reproductor) y las demás
 * pantallas suben encima. Main se encoge y oscurece detrás, como una hoja
 * de iOS; mientras está totalmente cubierta deja de dibujarse.
 *
 * El reproductor no usa AnimatedVisibility: su posición es la fracción de
 * [PlayerExpansion], que también mueven los arrastres, así que la capa y
 * el fondo siguen al dedo y la animación de soltar parte de su velocidad.
 */
@Composable
fun AppNavHost(nav: NavController, content: @Composable (AppScreen) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val playerInStack = AppScreen.FullPlayer in nav.stack
    val expansion = rememberPlayerExpansion(initiallyOpen = playerInStack)
    LaunchedEffect(playerInStack) { expansion.syncWith(playerInStack) }

    val lyricsState = remember { MutableTransitionState(AppScreen.Lyrics in nav.stack) }
    lyricsState.targetState = AppScreen.Lyrics in nav.stack
    val lyricsDepth by animateFloatAsState(
        targetValue = if (AppScreen.Lyrics in nav.stack) 1f else 0f,
        animationSpec = tween(if (AppScreen.Lyrics in nav.stack) 420 else 300, easing = EighthTheme.EasingOutCubic),
        label = "lyricsDepth",
    )
    val playerComposed by remember {
        derivedStateOf { expansion.fraction.value > 0f || expansion.dragging }
    }
    val cornerPx = with(LocalDensity.current) { 34.dp.toPx() }
    val miniCornerPx = with(LocalDensity.current) { EighthTheme.RadiusXL.toPx() }

    CompositionLocalProvider(LocalPlayerExpansion provides expansion) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { expansion.heightPx = it.height.toFloat().coerceAtLeast(1f) },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val f = expansion.fraction.value.coerceIn(0f, 1f)
                        val depth = maxOf(f, lyricsDepth)
                        // Totalmente cubierta y quieta: no se dibuja (las capas
                        // son opacas), ahorra el grid entero en cada frame.
                        val playerCovers = f >= 1f && !expansion.dragging && !expansion.fraction.isRunning
                        val lyricsCovers = lyricsState.isIdle && lyricsState.currentState && !nav.revealingUnderlay
                        val s = 1f - 0.06f * depth
                        scaleX = s
                        scaleY = s
                        alpha = if (playerCovers || lyricsCovers) 0f else 1f - 0.45f * depth
                    },
            ) {
                holder.SaveableStateProvider(AppScreen.Main.saveKey) { content(AppScreen.Main) }
            }
            if (playerComposed || playerInStack) {
                val surface = EighthTheme.PopupSurface
                // Transformación de contenedor: la tarjeta del mini player
                // crece hasta ocupar la pantalla (posición, ancho y esquinas)
                // y el reproductor aparece dentro, subiendo y enfocándose.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val f = expansion.fraction.value.coerceIn(0f, 1f)
                            if (f >= 1f) {
                                clip = false
                                return@graphicsLayer
                            }
                            val mini = expansion.miniBounds ?: Rect(
                                left = 16.dp.toPx(),
                                top = size.height - 110.dp.toPx(),
                                right = size.width - 16.dp.toPx(),
                                bottom = size.height - 30.dp.toPx(),
                            )
                            // El ancho llega antes que el alto: la tarjeta se
                            // ensancha y luego se estira hacia arriba.
                            val fw = EighthTheme.EasingOutCubic.transform(f)
                            val bounds = Rect(
                                left = lerp(mini.left, 0f, fw),
                                top = lerp(mini.top, 0f, f),
                                right = lerp(mini.right, size.width, fw),
                                bottom = lerp(mini.bottom, size.height, fw),
                            )
                            shape = MorphShape(bounds, lerp(miniCornerPx, cornerPx, f))
                            clip = true
                        }
                        .drawBehind {
                            if (expansion.fraction.value < 1f) drawRect(surface)
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val f = expansion.fraction.value.coerceIn(0f, 1f)
                                if (f >= 1f) return@graphicsLayer
                                // Entra algo después que la tarjeta y desde abajo.
                                alpha = ((f - 0.06f) / 0.45f).coerceIn(0f, 1f)
                                val top = expansion.miniBounds?.top ?: size.height
                                // Sin escala: el fondo difuminado del player se
                                // salía por los lados y se veían bandas claras.
                                translationY = (1f - f) * top * 0.85f
                            },
                    ) {
                        holder.SaveableStateProvider(AppScreen.FullPlayer.saveKey) { content(AppScreen.FullPlayer) }
                    }
                }
            }
            AnimatedVisibility(lyricsState, enter = layerEnter, exit = layerExit, label = "lyricsLayer") {
                holder.SaveableStateProvider(AppScreen.Lyrics.saveKey) { content(AppScreen.Lyrics) }
            }
        }
    }
}

/** Rectángulo redondeado en una posición arbitraria de la capa. */
private class MorphShape(private val bounds: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
