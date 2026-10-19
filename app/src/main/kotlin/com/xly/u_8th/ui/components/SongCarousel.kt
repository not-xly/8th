package com.xly.u_8th.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Carrusel anterior / actual / siguiente que sigue al dedo. Al soltar con
 * suficiente recorrido o velocidad la pista se desliza hasta el vecino y se
 * pide el cambio de canción; cuando la canción cambia de verdad, la pista se
 * recoloca sin salto visual. Un cambio que llega por otro camino (botones,
 * notificación, coche) anima igual, deduciendo el sentido.
 */
@Stable
class SongCarouselState internal constructor(private val scope: CoroutineScope) {
    internal val offset = Animatable(0f)

    /** Distancia entre ranuras (ancho del elemento + separación), px. */
    internal var slotPx by mutableFloatStateOf(0f)
    internal var hasNext by mutableStateOf(false)
    internal var hasPrevious by mutableStateOf(false)

    /** Sentido de un swipe confirmado que espera el cambio de canción. */
    private var pendingDir = 0
    private var pendingToken by mutableIntStateOf(0)
    private var timeoutJob: Job? = null

    fun dragTo(totalDx: Float) {
        val w = slotPx
        if (w <= 0f || pendingDir != 0) return
        val blocked = (totalDx < 0 && !hasNext) || (totalDx > 0 && !hasPrevious)
        // Sin vecino en ese lado: resistencia fuerte (se nota que no hay más).
        val x = if (blocked) totalDx * 0.22f else totalDx.coerceIn(-w, w)
        scope.launch { offset.snapTo(x) }
    }

    /** Devuelve true si el swipe cambia de canción. */
    fun release(velocityX: Float, onNext: () -> Unit, onPrevious: () -> Unit): Boolean {
        val w = slotPx
        if (w <= 0f || pendingDir != 0) return false
        val x = offset.value
        val dir = when {
            velocityX <= -FLING_PX_S -> if (hasNext) 1 else 0
            velocityX >= FLING_PX_S -> if (hasPrevious) -1 else 0
            x < -w * COMMIT_FRACTION -> if (hasNext) 1 else 0
            x > w * COMMIT_FRACTION -> if (hasPrevious) -1 else 0
            else -> 0
        }
        if (dir == 0) {
            scope.launch { offset.animateTo(0f, SettleSpring, velocityX) }
            return false
        }
        pendingDir = dir
        val token = ++pendingToken
        scope.launch { offset.animateTo(-dir * w, SlideSpring, velocityX) }
        if (dir > 0) onNext() else onPrevious()
        // Si la canción no llega a cambiar (fin de cola, error), se recoloca.
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(PENDING_TIMEOUT_MS)
            if (pendingDir != 0 && token == pendingToken) {
                pendingDir = 0
                offset.animateTo(0f, SettleSpring)
            }
        }
        return true
    }

    /** Vuelve al centro si el gesto se abandona (lo capturó un hijo). */
    fun cancel() {
        if (pendingDir == 0 && offset.value != 0f) scope.launch { offset.animateTo(0f, SettleSpring) }
    }

    internal suspend fun onSongChanged(inferredDir: Int) {
        val dir = if (pendingDir != 0) pendingDir else inferredDir
        pendingDir = 0
        timeoutJob?.cancel()
        val w = slotPx
        if (dir == 0 || w <= 0f) {
            offset.snapTo(0f)
            return
        }
        // El contenido acaba de desplazarse una ranura: se compensa en el
        // offset para que lo visible no salte, y se continúa hasta el centro
        // con la velocidad que llevara la animación.
        val velocity = offset.velocity
        offset.snapTo(offset.value + dir * w)
        offset.animateTo(0f, SlideSpring, velocity)
    }

    private companion object {
        const val FLING_PX_S = 700f
        const val COMMIT_FRACTION = 0.32f
        const val PENDING_TIMEOUT_MS = 1500L
        val SlideSpring = spring<Float>(dampingRatio = 1f, stiffness = 420f)
        val SettleSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 520f)
    }
}

@Composable
fun rememberSongCarouselState(): SongCarouselState {
    val scope = rememberCoroutineScope()
    return remember(scope) { SongCarouselState(scope) }
}

/**
 * Pinta [previous], [current] y [next] en ranuras contiguas desplazadas por
 * el offset del [state]. Cada canción conserva su nodo (y su carátula ya
 * decodificada) al pasar de una ranura a otra gracias a `key`.
 */
@Composable
fun SongCarousel(
    state: SongCarouselState,
    previous: Song?,
    current: Song,
    next: Song?,
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    content: @Composable (song: Song, isCurrent: Boolean) -> Unit,
) {
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    state.hasNext = next != null
    state.hasPrevious = previous != null

    var lastSong by remember { mutableStateOf(current) }
    LaunchedEffect(current.playUri) {
        val old = lastSong
        lastSong = current
        if (old.playUri == current.playUri) return@LaunchedEffect
        val dir = when (old.playUri) {
            previous?.playUri -> 1
            next?.playUri -> -1
            else -> 0
        }
        state.onSongChanged(dir)
    }

    Box(modifier.onSizeChanged { state.slotPx = it.width + gapPx }) {
        val slots = listOf(-1 to previous, 0 to current, 1 to next)
        for ((slot, song) in slots) {
            if (song == null) continue
            key(song.playUri) {
                Box(
                    Modifier.graphicsLayer {
                        val x = slot * state.slotPx + state.offset.value
                        translationX = x
                        // Los vecinos se atenúan un poco fuera del centro.
                        val d = if (state.slotPx > 0f) (abs(x) / state.slotPx).coerceIn(0f, 1f) else 0f
                        alpha = 1f - 0.35f * d
                    },
                ) {
                    content(song, slot == 0)
                }
            }
        }
    }
}
