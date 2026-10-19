package com.xly.u_8th.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Posición del reproductor completo entre el mini player (0) y la pantalla
 * entera (1). Es una única fuente de verdad para los tres caminos: el
 * arrastre hacia arriba desde el mini player, el arrastre hacia abajo desde
 * el reproductor y la navegación (tap, botón, atrás). Así la capa sigue al
 * dedo en ambos sentidos y, al soltar, continúa con la velocidad del gesto.
 */
@Stable
class PlayerExpansion internal constructor(private val scope: CoroutineScope, initiallyOpen: Boolean) {

    val fraction = Animatable(if (initiallyOpen) 1f else 0f, visibilityThreshold = 0.0005f)

    /** Alto de la pantalla en px: convierte el arrastre en fracción. */
    var heightPx by mutableFloatStateOf(1f)
        internal set

    /**
     * Tarjeta del mini player en coordenadas de raíz: el reproductor crece
     * desde ella al abrirse y vuelve a ella al cerrarse.
     */
    var miniBounds by mutableStateOf<Rect?>(null)

    /** Un dedo está moviendo la capa (la navegación no debe animarla). */
    var dragging by mutableStateOf(false)
        private set

    /** Estado al que se dirige (o en el que está) fuera de un arrastre. */
    internal var targetOpen = initiallyOpen
        private set

    /** Arrastre desde el mini player: [totalDy] negativo = hacia arriba. */
    fun dragFromCollapsed(totalDy: Float) = dragTo(-totalDy / heightPx)

    /** Arrastre desde el reproductor: [totalDy] positivo = hacia abajo. */
    fun dragFromExpanded(totalDy: Float) = dragTo(1f - totalDy / heightPx)

    private fun dragTo(value: Float) {
        dragging = true
        // UNDISPATCHED: el snap se aplica ya, no en el siguiente frame (un
        // frame de retraso por evento se notaba como lag al arrastrar).
        scope.launch(start = CoroutineStart.UNDISPATCHED) { fraction.snapTo(value.coerceIn(0f, 1f)) }
    }

    /**
     * Fin del arrastre con la velocidad vertical en px/s. Un fling decide
     * por su sentido; si no, basta con recorrer un 10 % de pantalla (un
     * gesto corto, como en las apps de música actuales).
     */
    fun release(velocityY: Float, nav: NavController) {
        if (!dragging) return
        dragging = false
        val f = fraction.value
        val open = when {
            velocityY < -FLING_PX_S -> true
            velocityY > FLING_PX_S -> false
            targetOpen -> f > 1f - DISTANCE
            else -> f > DISTANCE
        }
        settle(open, nav, initialVelocity = -velocityY / heightPx)
    }

    /** Arrastre interrumpido (lo capturó un hijo): vuelve a donde estaba. */
    fun cancel(nav: NavController) {
        if (!dragging) return
        dragging = false
        settle(targetOpen, nav, initialVelocity = 0f)
    }

    private fun settle(open: Boolean, nav: NavController, initialVelocity: Float) {
        targetOpen = open
        val inStack = AppScreen.FullPlayer in nav.stack
        if (open && !inStack) nav.push(AppScreen.FullPlayer)
        if (!open && inStack) nav.pop()
        animate(open, initialVelocity)
    }

    /** La pila cambió por navegación (tap en el mini player, atrás…). */
    internal fun syncWith(open: Boolean) {
        if (dragging || open == targetOpen) return
        targetOpen = open
        animate(open, fraction.velocity)
    }

    private fun animate(open: Boolean, initialVelocity: Float) {
        scope.launch {
            fraction.animateTo(
                target(open),
                spring(dampingRatio = 1f, stiffness = if (open) 300f else 380f),
                initialVelocity,
            )
        }
    }

    private fun target(open: Boolean) = if (open) 1f else 0f

    private companion object {
        const val FLING_PX_S = 450f
        const val DISTANCE = 0.1f
    }
}

@Composable
fun rememberPlayerExpansion(initiallyOpen: Boolean): PlayerExpansion {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerExpansion(scope, initiallyOpen) }
}

val LocalPlayerExpansion = staticCompositionLocalOf<PlayerExpansion> {
    error("PlayerExpansion no provisto: AppNavHost debe envolver las pantallas")
}
