package com.xly.u_8th.ui.screens.lyrics

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.TransformOrigin
import com.xly.u_8th.ui.components.GlassPill
import kotlin.math.abs
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xly.u_8th.playback.PlayerBus
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.data.LrcLine
import com.xly.u_8th.playback.PlayerConnection
import com.xly.u_8th.ui.theme.EighthTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

@Composable
internal fun SyncedLyricsList(
    lines: List<LrcLine>,
    connection: PlayerConnection,
    offsetMs: Long,
) {
    val listState = rememberLazyListState()
    // El State se lee DENTRO de derivedStateOf: antes se capturaba el
    // parámetro `positionMs` de la primera composición en un
    // `remember(lines)` y la línea activa podía quedarse congelada.
    val position = PlayerBus.positionMs.collectAsStateWithLifecycle()
    val offset by rememberUpdatedState(offsetMs)
    var returnToCurrent by remember { mutableIntStateOf(0) }
    // true mientras el usuario ha desplazado la lista a mano y el
    // auto-scroll está en pausa: muestra la píldora "Volver".
    var userScrolled by remember { mutableStateOf(false) }
    // `derivedStateOf` y no `remember(lines, positionMs)`: el ticker de
    // reproducción escribe positionMs cada 500ms, y usarlo como clave
    // invalidaba la lista Y todas sus filas visibles (cada una con cuatro
    // animate*AsState) aunque la línea activa no hubiera cambiado. Aquí solo
    // se recomputa y solo se invalida cuando cambia el índice resultante.
    val currentIndex by remember(lines) {
        derivedStateOf { lastLineAtOrBefore(lines, position.value - offset) }
    }

    // Pause auto-scroll for a grace period after the user drags the list.
    var suppressUntil by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    dragging = true
                    userScrolled = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    suppressUntil = SystemClock.uptimeMillis() + 4000
                }
            }
        }
    }
    LaunchedEffect(returnToCurrent) {
        if (returnToCurrent > 0) suppressUntil = 0L
    }

    // Centra la línea [index] en mitad del viewport. La distancia de un
    // ítem al inicio del viewport es (offset - viewportStartOffset); el
    // desplazamiento necesario es esa distancia menos la objetivo:
    // centro del viewport menos media altura de la línea.
    suspend fun centerLine(index: Int, animate: Boolean) {
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        if (viewport <= 0) return
        var info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (info == null) {
            // Fuera de pantalla: ancla el ítem sin animación; el ajuste de
            // centrado (ya pequeño) sí puede animar.
            listState.scrollToItem(index)
            info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        }
        val itemDistance = (info?.offset ?: listState.layoutInfo.viewportStartOffset) -
            listState.layoutInfo.viewportStartOffset
        val halfItem = (info?.size ?: 0) / 2f
        val shift = itemDistance - (0.5f * viewport - halfItem)
        if (animate) {
            listState.animateScrollBy(shift, tween(320, easing = EighthTheme.EasingStandard))
        } else {
            listState.scrollBy(shift)
        }
    }

    var initialJumpDone by remember(lines) { mutableStateOf(false) }
    LaunchedEffect(lines, currentIndex, dragging, suppressUntil, returnToCurrent) {
        if (lines.isEmpty() || currentIndex < 0 || dragging) return@LaunchedEffect
        delay(lyricsResumeDelay(SystemClock.uptimeMillis(), suppressUntil))
        userScrolled = false
        if (!initialJumpDone) {
            // Espera al primer pase de layout y salta sin animación dejando
            // la línea cantada centrada en pantalla.
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
                .filter { it }
                .first()
            centerLine(currentIndex, animate = false)
            initialJumpDone = true
            return@LaunchedEffect
        }
        centerLine(currentIndex, animate = true)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().fadingEdges(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 48.dp),
        ) {
            itemsIndexed(
                lines,
                // Clave posicional: la lista es inmutable mientras
                // suena la misma canción. Una clave por timestamp
                // crasheaba con dos líneas en el mismo instante
                // (duetos, tags repetidos): LazyColumn exige claves
                // únicas.
                key = { index, _ -> index },
            ) { index, line ->
                val isCurrent = index == currentIndex
                val distance = abs(index - currentIndex)
                val textColor by animateColorAsState(
                    when {
                        isCurrent -> EighthTheme.TextPrimary
                        distance == 1 -> EighthTheme.TextPrimary.copy(alpha = 0.42f)
                        else -> EighthTheme.TextPrimary.copy(alpha = 0.26f)
                    },
                    tween(320, easing = EighthTheme.EasingOutCubic),
                    label = "lyricColor",
                )
                val scale by animateFloatAsState(
                    if (isCurrent) 1f else 0.94f,
                    spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    label = "lyricScale",
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            onClickLabel = "Reproducir desde esta línea",
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            connection.launch { seekTo((line.timestampMs + offsetMs).coerceAtLeast(0L)) }
                        }
                        .padding(vertical = 9.dp),
                ) {
                    // Tipografía fija en todas las líneas: solo animan escala y
                    // color, así la línea activa nunca cambia de ajuste.
                    Text(
                        line.text.ifBlank { "♪" },
                        color = textColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 31.sp,
                        letterSpacing = (-0.4).sp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                    )
                }
            }
        }
        AnimatedVisibility(
            userScrolled,
            enter = fadeIn(tween(180)) + slideInVertically(tween(240, easing = EighthTheme.EasingOutCubic)) { it },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        ) {
            GlassPill(
                Icons.Rounded.MyLocation,
                "Volver a la línea actual",
                onClick = {
                    userScrolled = false
                    returnToCurrent++
                },
            )
        }
    }
}

/** Last line whose timestamp is <= [positionMs] (binary search, -1 if none). */
private fun lastLineAtOrBefore(lines: List<LrcLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timestampMs <= positionMs) {
            idx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return idx
}
