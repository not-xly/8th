package com.xly.u_8th.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.AppStrings
import com.xly.u_8th.util.fmtMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes embedded album art bytes into an [ImageBitmap] off the main
 * thread, keyed by the bytes identity; null while decoding (placeholder
 * shows until then, crossfaded by the callers).
 */
@Composable
fun rememberArtBitmap(art: ByteArray?, sample: Int = 1): ImageBitmap? {
    if (art == null || art.isEmpty()) return null
    return produceState<ImageBitmap?>(initialValue = null, art, sample) {
        value = withContext(Dispatchers.Default) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(art, 0, art.size, opts)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }.value
}

fun songSubtitle(song: Song): String {
    val who = song.artist ?: song.album ?: AppStrings.UNKNOWN
    val d = song.durationMs
    return if (d != null && d > 0) "$who • ${fmtMs(d)}" else who
}

/** Small square glass container with an icon (no art available). */
@Composable
fun ArtPlaceholder(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = 10.dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    contentDescription: String? = null,
    fill: Color = EighthTheme.GlassSurfaceLight.copy(alpha = 0.9f),
) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(fill)
            .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SongTile(
    song: Song,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onAddToNext: () -> Unit,
    onAddToEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = animatedAura()
    val auraSoft = lerp(EighthTheme.PrimarySoft, aura, 0.6f)
    val fill by animateColorAsState(
        targetValue = if (isCurrent) EighthTheme.SelectedCard.copy(alpha = 0.92f)
        else EighthTheme.GlassSurface.copy(alpha = 0.62f),
        animationSpec = tween(420, easing = EighthTheme.EasingStandard),
        label = "songTileFill",
    )
    val art = rememberArtBitmap(song.albumArt, sample = 2)

    Box(modifier.padding(bottom = 7.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(EighthTheme.RadiusM))
                .background(fill)
                .then(
                    if (isCurrent) {
                        Modifier.border(
                            BorderStroke(1.dp, aura.copy(alpha = 0.88f)),
                            RoundedCornerShape(EighthTheme.RadiusM),
                        )
                    } else {
                        Modifier.border(
                            BorderStroke(0.7.dp, EighthTheme.GlassBorder),
                            RoundedCornerShape(EighthTheme.RadiusM),
                        )
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                val phFill by animateColorAsState(
                    targetValue = if (isCurrent) aura.copy(alpha = 0.16f)
                    else EighthTheme.GlassSurfaceLight.copy(alpha = 0.9f),
                    animationSpec = tween(420, easing = EighthTheme.EasingStandard),
                    label = "songTilePhFill",
                )
                val phBorder by animateColorAsState(
                    targetValue = if (isCurrent) aura.copy(alpha = 0.24f) else EighthTheme.GlassBorder,
                    animationSpec = tween(420, easing = EighthTheme.EasingStandard),
                    label = "songTilePhBorder",
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(phFill)
                        .border(BorderStroke(0.7.dp, phBorder), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isCurrent) Icons.Rounded.Equalizer else Icons.Rounded.MusicNote,
                        null,
                        tint = if (isCurrent) auraSoft else EighthTheme.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.displayTitle,
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = (-0.2).sp,
                    color = if (isCurrent) auraSoft else EighthTheme.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    songSubtitle(song),
                    fontSize = 12.sp,
                    color = EighthTheme.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        null,
                        tint = EighthTheme.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = EighthTheme.GlassSurface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(EighthTheme.RadiusS),
                    border = BorderStroke(0.7.dp, EighthTheme.GlassBorder),
                ) {
                    DropdownMenuItem(
                        text = { Text("Reproducir a continuación", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistPlay,
                                null,
                                tint = EighthTheme.PrimarySoft,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onAddToNext()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir al final de la cola", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Queue,
                                null,
                                tint = EighthTheme.Primary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onAddToEnd()
                        },
                    )
                }
            }
        }
    }
}
