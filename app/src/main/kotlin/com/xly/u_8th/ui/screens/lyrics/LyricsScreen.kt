package com.xly.u_8th.ui.screens.lyrics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.ui.components.AquaSlider
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.screens.fullplayer.GradientBackdrop
import com.xly.u_8th.ui.screens.fullplayer.PlayerTopBar
import com.xly.u_8th.ui.screens.fullplayer.playerPanDetector
import com.xly.u_8th.ui.screens.fullplayer.rememberDecodedArt
import com.xly.u_8th.ui.screens.fullplayer.rememberPlayerConnection
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.AppStrings
import com.xly.u_8th.util.fmtMs
import kotlin.math.roundToLong

/**
 * Full lyrics screen, full lyrics screen:
 * glass app bar with two-line title, small art, expanded lyrics panel and
 * mini transport controls. Swipe down (slow or fling) returns to the player.
 */
@Composable
fun LyricsScreen(nav: NavController) {
    val connection = rememberPlayerConnection()
    val queue by PlayerBus.queue.collectAsState()
    val queueIndex by PlayerBus.queueIndex.collectAsState()
    val playing by PlayerBus.playing.collectAsState()
    val positionMs by PlayerBus.positionMs.collectAsState()
    val durationMs by PlayerBus.durationMs.collectAsState()
    val aura = animatedAura()

    BackHandler { nav.pop() }

    val song = queue.getOrNull(queueIndex)

    // Keep the previous art when the new song has none .
    val decodedArt = rememberDecodedArt(song?.albumArt)
    var lastArt by remember { mutableStateOf(decodedArt) }
    if (decodedArt != null) lastArt = decodedArt
    val shownArt = decodedArt ?: lastArt

    val slowDragPx = with(LocalDensity.current) { 60.dp.toPx() }

    GradientBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .playerPanDetector { _, vy, _, dy ->
                    // Slow drag down or a downward fling closes the screen.
                    if (dy > slowDragPx || vy > 700f) nav.pop()
                },
        ) {
            PlayerTopBar(
                leading = {
                    GlassIconButton(Icons.Rounded.Close, null, onClick = { nav.pop() })
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            song?.displayTitle ?: "",
                            color = EighthTheme.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song?.artist ?: song?.album ?: AppStrings.UNKNOWN,
                            color = EighthTheme.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            if (shownArt != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(140.dp)) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(aura.copy(alpha = 0.28f), Color.Transparent),
                                center = center,
                                radius = size.minDimension / 2f,
                            ),
                            radius = size.minDimension / 2f,
                        )
                    }
                    Image(
                        bitmap = shownArt,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(
                        BorderStroke(0.7.dp, EighthTheme.GlassBorder),
                        RoundedCornerShape(16.dp),
                    ),
            ) {
                LyricsView(
                    song = song,
                    positionMs = positionMs,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(12.dp))

            // Mini transport controls.
            Column(Modifier.padding(horizontal = 24.dp)) {
                var scrub by remember { mutableStateOf<Float?>(null) }
                var scrubbing by remember { mutableStateOf(false) }
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
                    onScrubStart = { scrubbing = true },
                    // AquaSlider's end callback captures a stale progress; the
                    // current drag value lives in `scrub` (snapshot state).
                    onScrubEnd = { _ ->
                        scrubbing = false
                        val v = scrub
                        scrub = null
                        val d = PlayerBus.durationMs.value
                        if (d > 0 && v != null) connection.launch { seekTo((v * d).roundToLong()) }
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
                    Text(
                        fmtMs(shownPos),
                        color = EighthTheme.TextSecondary,
                        fontSize = 11.sp,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        fmtMs(durationMs),
                        color = EighthTheme.TextSecondary,
                        fontSize = 11.sp,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainControl(Icons.Rounded.SkipPrevious, "Anterior") {
                        connection.launch { prev() }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(76.dp)) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(aura.copy(alpha = 0.32f), Color.Transparent),
                                    center = center,
                                    radius = size.minDimension / 2f,
                                ),
                                radius = size.minDimension / 2f,
                            )
                        }
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(aura)
                                .clickable(
                                    interactionSource = MutableInteractionSource(),
                                    indication = null,
                                ) { connection.launch { togglePlay() } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pausa" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    PlainControl(Icons.Rounded.SkipNext, "Siguiente") {
                        connection.launch { next() }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Bare icon button (no glass chip), plain icon button. */
@Composable
private fun PlainControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = MutableInteractionSource(),
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = EighthTheme.TextSecondary,
            modifier = Modifier.size(28.dp),
        )
    }
}
