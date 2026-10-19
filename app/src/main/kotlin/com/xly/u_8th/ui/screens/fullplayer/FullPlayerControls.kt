package com.xly.u_8th.ui.screens.fullplayer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.components.AnimatedPlayPauseIcon
import com.xly.u_8th.ui.components.pressClickable
import com.xly.u_8th.ui.components.rememberArtBitmap
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.lerpColor
import com.xly.u_8th.util.AppStrings

/** Prev/next: icono grande sin caja, solo con la escala al pulsar. */
@Composable
internal fun TransportIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .pressClickable(pressedScale = 0.8f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = EighthTheme.TextPrimary, modifier = Modifier.size(38.dp))
    }
}

/** Album art + title + artist as a single animatable block. */
@Composable
internal fun SongShowcase(
    song: Song,
    aura: Color,
    playing: Boolean,
    onArtistTap: (String) -> Unit,
) {
    val art = rememberArtBitmap(song.albumArt, targetSize = 320.dp)
    val artShape = RoundedCornerShape(EighthTheme.RadiusXL)
    // En pausa la carátula "se retira" un poco, como en Apple Music.
    val artScale by animateFloatAsState(
        targetValue = if (playing) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "artScale",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                }
                .shadow(if (playing) 28.dp else 10.dp, artShape, clip = false, ambientColor = aura, spotColor = aura)
                .clip(artShape)
                .background(EighthTheme.GlassSurface)
                .border(BorderStroke(0.9.dp, EighthTheme.GlassBorderStrong), artShape),
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
        Spacer(Modifier.height(30.dp))
        // Una línea con marquee: el ellipsis a dos líneas desplazaba los
        // controles según la longitud del título.
        Text(
            song.displayTitle,
            color = EighthTheme.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2500),
        )
        Spacer(Modifier.height(4.dp))
        // Sin artista no hay panel que abrir: antes se pasaba el nombre del
        // álbum como si fuera el artista y el panel salía vacío.
        val artistName = Artists.primaryArtist(song.artist)
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (artistName == null) Modifier
                    else Modifier.pressClickable(pressedScale = 0.95f) { onArtistTap(artistName) },
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                song.artist ?: song.album ?: AppStrings.UNKNOWN,
                color = EighthTheme.accent(aura),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (artistName != null) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = EighthTheme.accent(aura),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Big 72dp gradient play/pause button with an aura glow and animated icon. */
@Composable
internal fun PlayPauseButton(
    playing: Boolean,
    aura: Color,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(112.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(aura.copy(alpha = if (playing) 0.38f else 0.22f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
            )
        }
        Box(
            Modifier
                .size(72.dp)
                .pressClickable(pressedScale = 0.88f, onClick = onClick)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(EighthTheme.accent(aura), lerpColor(EighthTheme.accent(aura), Color.Black, 0.25f)),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                )
                .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.18f)), shape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedPlayPauseIcon(playing, tint = Color.White, size = 38.dp)
        }
    }
}
