package com.xly.u_8th.ui.screens.lyrics

import com.xly.u_8th.ui.components.rememberWithCatalogArt
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.ui.components.AnimatedPlayPauseIcon
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.components.pressClickable
import com.xly.u_8th.ui.components.rememberArtBitmap
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.screens.fullplayer.GradientBackdrop
import com.xly.u_8th.ui.screens.fullplayer.PlayerTopBar
import com.xly.u_8th.ui.screens.fullplayer.SeekRow
import com.xly.u_8th.ui.screens.fullplayer.dragDismissLayer
import com.xly.u_8th.ui.screens.fullplayer.playerPanDetector
import com.xly.u_8th.ui.screens.fullplayer.rememberDragDismissState
import com.xly.u_8th.ui.screens.fullplayer.rememberPlayerConnection
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.lerpColor
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.AppStrings
import kotlin.math.abs

/**
 * Full lyrics screen over the blurred-art backdrop: small title bar with
 * the cover thumbnail, the lyrics panel and mini transport controls. Drag
 * down (it follows the finger) or fling down to return to the player.
 */
@Composable
fun LyricsScreen(nav: NavController) {
    val connection = rememberPlayerConnection()
    val queue by PlayerBus.queue.collectAsStateWithLifecycle()
    val queueIndex by PlayerBus.queueIndex.collectAsStateWithLifecycle()
    val playing by PlayerBus.playing.collectAsStateWithLifecycle()
    val aura = animatedAura()
    val dragState = rememberDragDismissState(nav)

    val song = queue.getOrNull(queueIndex)?.rememberWithCatalogArt()

    // Keep the previous art when the new song has none. The retention happens
    // in an effect: writing snapshot state in the composition body schedules
    // an extra invalidation pass and can leave lastArt and shownArt
    // disagreeing for one frame.
    val decodedArt = rememberArtBitmap(song?.albumArt, 48.dp)
    var lastArt by remember { mutableStateOf(decodedArt) }
    LaunchedEffect(decodedArt) {
        if (decodedArt != null) lastArt = decodedArt
    }
    val shownArt = decodedArt ?: lastArt

    GradientBackdrop(modifier = Modifier.dragDismissLayer(dragState), art = song?.albumArt, aura = aura) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .playerPanDetector(onVerticalDrag = dragState::drag, onCancel = dragState::settle) { vx, vy, _, _ ->
                    if (abs(vx) > abs(vy)) dragState.settle() else dragState.release(vy) { nav.pop() }
                },
        ) {
            PlayerTopBar(
                leading = {
                    GlassIconButton(
                        Icons.Rounded.KeyboardArrowDown,
                        "Volver al reproductor",
                        onClick = { nav.pop() },
                        size = EighthTheme.IconButtonM,
                    )
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (shownArt != null) {
                            Image(
                                bitmap = shownArt,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f, fill = false)) {
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
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
            LyricsView(
                song = song,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // Mini transport controls.
            Column(Modifier.padding(horizontal = 24.dp)) {
                SeekRow(gestureKey = song?.playUri, connection = connection, timeFontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainControl(Icons.Rounded.SkipPrevious, "Anterior") {
                        connection.launch { prev() }
                    }
                    Spacer(Modifier.width(20.dp))
                    Box(
                        Modifier
                            .size(56.dp)
                            .pressClickable(pressedScale = 0.86f) { connection.launch { togglePlay() } }
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(EighthTheme.accent(aura), lerpColor(EighthTheme.accent(aura), Color.Black, 0.25f))))
                            .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.18f)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedPlayPauseIcon(playing, tint = Color.White, size = 30.dp)
                    }
                    Spacer(Modifier.width(20.dp))
                    PlainControl(Icons.Rounded.SkipNext, "Siguiente") {
                        connection.launch { next() }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** Bare icon button (no glass chip) with press feedback. */
@Composable
private fun PlainControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .pressClickable(pressedScale = 0.8f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = EighthTheme.TextPrimary,
            modifier = Modifier.size(32.dp),
        )
    }
}
