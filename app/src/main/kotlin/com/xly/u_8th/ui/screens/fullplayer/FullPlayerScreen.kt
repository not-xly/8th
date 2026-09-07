package com.xly.u_8th.ui.screens.fullplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.ui.components.AquaSlider
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.nav.AppScreen
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.AppStrings
import com.xly.u_8th.util.fmtMs
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Full player, directional
 * song showcase, scrubber, transport controls and fling gestures
 * (left/right skip, up opens the queue sheet, down closes).
 */
@Composable
fun FullPlayerScreen(nav: NavController) {
    val connection = rememberPlayerConnection()
    val queue by PlayerBus.queue.collectAsState()
    val queueIndex by PlayerBus.queueIndex.collectAsState()
    val queueLabel by PlayerBus.queueLabel.collectAsState()
    val playing by PlayerBus.playing.collectAsState()
    val shuffleEnabled by PlayerBus.shuffleEnabled.collectAsState()
    val durationMs by PlayerBus.durationMs.collectAsState()
    val positionMs by PlayerBus.positionMs.collectAsState()
    val aura = animatedAura()

    BackHandler { nav.pop() }

    var showQueueSheet by remember { mutableStateOf(false) }
    var artistSheetName by remember { mutableStateOf<String?>(null) }

    if (queue.isEmpty()) {
        GradientBackdrop {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                PlayerTopBar(
                    leading = {
                        GlassIconButton(Icons.Rounded.KeyboardArrowDown, null, onClick = { nav.pop() })
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

    val song = queue[queueIndex.coerceIn(0, queue.size - 1)]

    // Direction of the last skip (1 = next): drives the showcase animation.
    var switchDir by remember { mutableIntStateOf(1) }
    fun skipNext() {
        switchDir = 1
        connection.launch { next() }
    }

    fun skipPrev() {
        switchDir = -1
        connection.launch { prev() }
    }

    // Manual scrub state; reset when the track changes (like the Dart index listener).
    var scrub by remember { mutableStateOf<Float?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(queueIndex) {
        scrub = null
        scrubbing = false
    }

    GradientBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PlayerTopBar(
                leading = {
                    GlassIconButton(Icons.Rounded.KeyboardArrowDown, null, onClick = { nav.pop() })
                },
                title = {
                    Text(
                        queueLabel.ifBlank { "Reproduciendo" },
                        color = EighthTheme.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.2).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailing = {
                    GlassIconButton(
                        Icons.Rounded.QueueMusic,
                        "Cola de reproducción",
                        onClick = { showQueueSheet = true },
                    )
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .playerPanDetector { vx, vy, _, _ ->
                        if (abs(vx) > abs(vy)) {
                            if (vx < -600f) skipNext() else if (vx > 600f) skipPrev()
                        } else {
                            if (vy < -700f) showQueueSheet = true else if (vy > 700f) nav.pop()
                        }
                    }
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag handle
                Box(
                    Modifier
                        .padding(bottom = 8.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.18f)),
                )
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = song,
                    transitionSpec = {
                        val dir = switchDir
                        (
                            fadeIn(tween(300, easing = EighthTheme.EasingOutCubic)) +
                                slideInHorizontally(tween(300, easing = EighthTheme.EasingOutCubic)) { full ->
                                    (dir * full * 0.3f).toInt()
                                }
                            ).togetherWith(
                            fadeOut(tween(300, easing = EighthTheme.EasingInCubic)) +
                                slideOutHorizontally(tween(300, easing = EighthTheme.EasingInCubic)) { full ->
                                    (-dir * full * 0.3f).toInt()
                                }
                        )
                    },
                    contentKey = { it.playUri },
                    label = "songShowcase",
                ) { current ->
                    SongShowcase(
                        song = current,
                        aura = aura,
                        onArtistTap = { artistSheetName = it },
                    )
                }
                Spacer(Modifier.height(24.dp))

                // Seek row: while scrubbing show the drag value, on end (or tap) seek.
                val progress = scrub
                    ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Column(Modifier.fillMaxWidth()) {
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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = EighthTheme.TextSecondary,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                        Text(
                            fmtMs(durationMs),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = EighthTheme.TextSecondary,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(
                        icon = Icons.Rounded.Shuffle,
                        contentDescription = if (shuffleEnabled) "Restaurar orden original" else "Aleatorio",
                        onClick = { connection.launch { setShuffled(!shuffleEnabled) } },
                        size = 44.dp,
                        iconSize = 20.dp,
                        active = shuffleEnabled,
                        radius = 12.dp,
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        onClick = { skipPrev() },
                        size = 44.dp,
                        iconSize = 28.dp,
                        radius = 12.dp,
                    )
                    PlayPauseButton(playing = playing, aura = aura) {
                        connection.launch { togglePlay() }
                    }
                    GlassIconButton(
                        icon = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        onClick = { skipNext() },
                        size = 44.dp,
                        iconSize = 28.dp,
                        radius = 12.dp,
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.Lyrics,
                        contentDescription = "Letra a pantalla completa",
                        onClick = { nav.push(AppScreen.Lyrics) },
                        size = 44.dp,
                        iconSize = 20.dp,
                        radius = 12.dp,
                    )
                }
                Spacer(Modifier.weight(1f))
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

/** Album art + title + artist as a single animatable block. */
@Composable
private fun SongShowcase(
    song: Song,
    aura: Color,
    onArtistTap: (String) -> Unit,
) {
    val art = rememberDecodedArt(song.albumArt)
    val artShape = RoundedCornerShape(EighthTheme.RadiusXL)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Aura glow behind the art (approximation of the aura box shadow).
            Canvas(Modifier.size(360.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(aura.copy(alpha = 0.28f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 280.dp)
                    .aspectRatio(1f)
                    .shadow(16.dp, artShape, clip = false)
                    .clip(artShape)
                    .background(EighthTheme.GlassSurface)
                    .border(BorderStroke(0.9.dp, Color.White.copy(alpha = 0.10f)), artShape),
            ) {
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        EighthTheme.GlassSurfaceLight.copy(alpha = 0.9f),
                                        EighthTheme.GlassSurface.copy(alpha = 0.7f),
                                    ),
                                    start = Offset.Zero,
                                    end = Offset.Infinite,
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Album,
                            contentDescription = null,
                            tint = EighthTheme.TextTertiary,
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }
                // Diagonal gloss overlay.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            ),
                        ),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            song.displayTitle,
            color = EighthTheme.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onArtistTap(Artists.primaryArtist(song.artist) ?: song.album ?: AppStrings.UNKNOWN)
                }
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                song.artist ?: song.album ?: AppStrings.UNKNOWN,
                color = aura.copy(alpha = 0.92f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = aura.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Big 68dp gradient play/pause button with an aura glow. */
@Composable
private fun PlayPauseButton(
    playing: Boolean,
    aura: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(EighthTheme.RadiusL)
    Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(100.dp)) {
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
                .size(68.dp)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(aura, aura.copy(alpha = 0.86f)),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                )
                .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.14f)), shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pausa" else "Reproducir",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
