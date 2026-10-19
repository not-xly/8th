package com.xly.u_8th.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.screens.fullplayer.playerPanDetector
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import kotlin.math.abs

/**
 * Mini reproductor flotante. Gestos:
 * - horizontal: carrusel con la canción anterior/siguiente que sigue al dedo;
 * - vertical hacia arriba: el reproductor completo sube con el dedo
 *   ([onExpandDrag] / [onExpandRelease]); al soltar decide abrir o volver;
 * - tap: abre el reproductor; la franja inferior es la barra de progreso.
 * [expandedFraction] (0 = cerrado) atenúa la tarjeta mientras la capa sube.
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
    onExpandDrag: (totalDy: Float) -> Unit,
    onExpandRelease: (velocityY: Float) -> Unit,
    onExpandCancel: () -> Unit,
    expandedFraction: () -> Float,
    modifier: Modifier = Modifier,
    /** Posición de la tarjeta (raíz): origen de la animación del player. */
    onCardBounds: (Rect) -> Unit = {},
) {
    val carousel = rememberSongCarouselState()
    var scrubbing by remember(song.playUri) { mutableStateOf(false) }
    var scrubRatio by remember(song.playUri) { mutableStateOf<Float?>(null) }
    var lastProgressInteractionMs by remember { mutableLongStateOf(0L) }
    var expanding by remember { mutableStateOf(false) }

    // Isla flotante: misma receta de cristal que la cápsula de la cabecera,
    // sobre un fondo casi opaco para que el grid no se lea a través.
    val cardShape = RoundedCornerShape(EighthTheme.RadiusXL)

    Box(modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
        Box(
            Modifier
                .onGloballyPositioned { onCardBounds(it.boundsInRoot()) }
                .graphicsLayer {
                    // Se desvanece en el primer tramo de la subida del reproductor.
                    alpha = (1f - expandedFraction() * 2.5f).coerceIn(0f, 1f)
                }
                .shadow(28.dp, cardShape, spotColor = Color.Black.copy(alpha = 0.55f), ambientColor = Color.Transparent)
                .background(EighthTheme.PopupSurface.copy(alpha = 0.94f), cardShape)
                .glass(cardShape)
                .semantics(mergeDescendants = true) {
                    onClick(label = "Abrir reproductor") {
                        onTap()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction("Anterior") { onPrevious(); true },
                        CustomAccessibilityAction("Siguiente") { onNext(); true },
                        CustomAccessibilityAction(if (playing) "Pausa" else "Reproducir") { onTogglePlay(); true },
                    )
                }
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
                .playerPanDetector(
                    onVerticalDrag = { dy ->
                        // Solo hacia arriba arranca la subida; una vez en
                        // marcha, el dedo puede volver a bajar.
                        if (dy < 0f || expanding) {
                            expanding = true
                            onExpandDrag(dy)
                        }
                    },
                    onHorizontalDrag = carousel::dragTo,
                    onCancel = {
                        if (expanding) onExpandCancel()
                        expanding = false
                        carousel.cancel()
                    },
                ) { vx, vy, totalDx, totalDy ->
                    if (abs(totalDx) > abs(totalDy)) {
                        carousel.release(vx, onNext = onNext, onPrevious = onPrevious)
                    } else if (expanding) {
                        onExpandRelease(vy)
                    }
                    expanding = false
                },
        ) {
            Box(Modifier.height(68.dp)) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 9.dp, end = 8.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Solo carátula y título se deslizan; los botones quedan fijos.
                    SongCarousel(
                        state = carousel,
                        previous = previousSong,
                        current = song,
                        next = nextSong,
                        gap = 24.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(EighthTheme.RadiusM)),
                    ) { item, _ ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            MiniArt(item)
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) { TitleColumn(item) }
                        }
                    }
                    MiniIconButton(Icons.Rounded.SkipPrevious, "Anterior", onPrevious)
                    Box(
                        Modifier
                            .size(44.dp)
                            .pressClickable(pressedScale = 0.86f, onClick = onTogglePlay)
                            .glass(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedPlayPauseIcon(playing, tint = Color.White, size = 24.dp)
                    }
                    MiniIconButton(Icons.Rounded.SkipNext, "Siguiente", onNext)
                    Spacer(Modifier.width(2.dp))
                }
                // Barra de progreso superpuesta al borde inferior.
                // SOLO la franja inferior (14dp) es interactiva: si el
                // slider cubriera la tarjeta entera, se comería los
                // toques de abrir el player y los swipes de cambio.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 22.dp, end = 22.dp, bottom = 1.dp),
                ) {
                    val progress = scrubRatio?.takeIf { scrubbing }
                        ?: (if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f)
                    AquaSlider(
                        progress = progress,
                        onSeek = { fraction ->
                            lastProgressInteractionMs = System.currentTimeMillis()
                            if (durationMs > 0 && !scrubbing) onSeek((durationMs * fraction).toLong())
                            if (scrubbing) scrubRatio = fraction
                        },
                        scrubbing = scrubbing,
                        onScrubStart = { fraction ->
                            lastProgressInteractionMs = System.currentTimeMillis()
                            scrubbing = true
                            // Ratio desde el punto tocado: con null la barra
                            // volvía a pintar la posición viva del
                            // reproductor y saltaba hacia atrás al agarrar.
                            scrubRatio = fraction
                        },
                        onScrubEnd = { fraction ->
                            if (durationMs > 0) onSeek((durationMs * fraction).toLong())
                            scrubbing = false
                            scrubRatio = null
                        },
                        gestureKey = song.playUri,
                        onScrubCancel = { scrubbing = false; scrubRatio = null },
                        touchHeight = 14.dp,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
