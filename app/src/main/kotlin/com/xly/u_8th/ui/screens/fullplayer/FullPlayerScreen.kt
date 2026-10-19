package com.xly.u_8th.ui.screens.fullplayer

import com.xly.u_8th.ui.components.rememberWithCatalogArt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.components.GlassPill
import com.xly.u_8th.ui.components.SongCarousel
import com.xly.u_8th.ui.components.rememberSongCarouselState
import com.xly.u_8th.ui.nav.LocalPlayerExpansion
import com.xly.u_8th.ui.nav.AppScreen
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import kotlin.math.abs

/**
 * Full player: song showcase over a blurred-art backdrop, scrubber,
 * transport (shuffle / prev / play / next / repeat) and fling gestures
 * (left/right skip, up opens the queue sheet, drag down closes).
 */
@Composable
fun FullPlayerScreen(nav: NavController) {
    val connection = rememberPlayerConnection()
    val queue by PlayerBus.queue.collectAsStateWithLifecycle()
    val queueIndex by PlayerBus.queueIndex.collectAsStateWithLifecycle()
    val queueLabel by PlayerBus.queueLabel.collectAsStateWithLifecycle()
    val playing by PlayerBus.playing.collectAsStateWithLifecycle()
    val shuffleEnabled by PlayerBus.shuffleEnabled.collectAsStateWithLifecycle()
    val loopMode by PlayerBus.loopMode.collectAsStateWithLifecycle()
    val aura = animatedAura()
    val expansion = LocalPlayerExpansion.current
    val carousel = rememberSongCarouselState()

    var showQueueSheet by remember { mutableStateOf(false) }
    var artistSheetName by remember { mutableStateOf<String?>(null) }

    if (queue.isEmpty()) {
        GradientBackdrop {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                PlayerTopBar(
                    leading = {
                        GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Minimizar reproductor", onClick = { nav.pop() }, size = EighthTheme.IconButtonM)
                    },
                    title = {
                        Text(
                            "Reproductor",
                            color = EighthTheme.TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Cola vacía", color = EighthTheme.TextSecondary, fontSize = 15.sp)
                }
            }
        }
        return
    }

    val song = queue[queueIndex.coerceIn(0, queue.size - 1)].rememberWithCatalogArt()

    val safeIndex = queueIndex.coerceIn(0, queue.size - 1)
    val loopAll = loopMode == LoopMode.ALL
    val nextSong = (queue.getOrNull(safeIndex + 1) ?: queue.first().takeIf { loopAll && queue.size > 1 })
        ?.rememberWithCatalogArt()
    val prevSong = (queue.getOrNull(safeIndex - 1) ?: queue.last().takeIf { loopAll && queue.size > 1 })
        ?.rememberWithCatalogArt()
    fun skipNext() = connection.launch { next() }
    fun skipPrev() = connection.launch { prev() }

    GradientBackdrop(art = song.albumArt, aura = aura) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .playerPanDetector(
                    onVerticalDrag = expansion::dragFromExpanded,
                    onHorizontalDrag = carousel::dragTo,
                    onCancel = {
                        expansion.cancel(nav)
                        carousel.cancel()
                    },
                ) { vx, vy, totalDx, totalDy ->
                    if (abs(totalDx) > abs(totalDy)) {
                        carousel.release(vx, onNext = ::skipNext, onPrevious = ::skipPrev)
                    } else {
                        expansion.release(vy, nav)
                        // Hacia arriba no hay nada que cerrar: abre la cola.
                        if (totalDy < 0f && vy < -700f) showQueueSheet = true
                    }
                },
        ) {
            PlayerTopBar(
                leading = {
                    GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Minimizar reproductor", onClick = { nav.pop() }, size = EighthTheme.IconButtonM)
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "REPRODUCIENDO",
                            color = EighthTheme.TextTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            queueLabel.ifBlank { "Cola" },
                            color = EighthTheme.TextPrimary.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                SongCarousel(
                    state = carousel,
                    previous = prevSong,
                    current = song,
                    next = nextSong,
                    gap = 56.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { item, isCurrent ->
                    SongShowcase(
                        song = item,
                        aura = aura,
                        playing = playing && isCurrent,
                        onArtistTap = { artistSheetName = it },
                    )
                }
                Spacer(Modifier.height(28.dp))

                // Seek row: while scrubbing show the drag value, on end (or tap) seek.
                SeekRow(
                    gestureKey = song.playUri,
                    connection = connection,
                    timeFontSize = 12.sp,
                    timeFontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(
                        icon = Icons.Rounded.Shuffle,
                        contentDescription = if (shuffleEnabled) "Restaurar orden original" else "Aleatorio",
                        onClick = { connection.launch { setShuffled(!shuffleEnabled) } },
                        size = EighthTheme.IconButtonL,
                        active = shuffleEnabled,
                    )
                    TransportIcon(Icons.Rounded.SkipPrevious, "Anterior") { skipPrev() }
                    PlayPauseButton(playing = playing, aura = aura) {
                        connection.launch { togglePlay() }
                    }
                    TransportIcon(Icons.Rounded.SkipNext, "Siguiente") { skipNext() }
                    GlassIconButton(
                        icon = if (loopMode == LoopMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = when (loopMode) {
                            LoopMode.OFF -> "Repetir: desactivado"
                            LoopMode.ALL -> "Repetir: toda la cola"
                            LoopMode.ONE -> "Repetir: esta canción"
                        },
                        onClick = { connection.launch { cycleLoop() } },
                        size = EighthTheme.IconButtonL,
                        active = loopMode != LoopMode.OFF,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    GlassPill(Icons.Rounded.Lyrics, "Letra", onClick = { nav.push(AppScreen.Lyrics) })
                    GlassPill(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        "Cola · ${safeIndex + 1}/${queue.size}",
                        onClick = { showQueueSheet = true },
                    )
                }
            }
        }
    }

    if (showQueueSheet) {
        QueueSheetForFullPlayer(onDismiss = { showQueueSheet = false })
    }
    artistSheetName?.let { name ->
        ArtistSheetForFullPlayer(artist = name, onDismiss = { artistSheetName = null })
    }
}
