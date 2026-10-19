package com.xly.u_8th.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Queue
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

/**
 * Decodes embedded album art bytes into an [ImageBitmap] off the main
 * thread, keyed by content (not identity) and backed by the process-wide
 * [ArtBitmapMemoryCache]: the initial value comes from the cache when the
 * art was decoded before, so recycled grid cells repaint instantly instead
 * of flashing the placeholder. null while decoding.
 */
@Composable
fun rememberArtBitmap(art: ByteArray?, targetSize: androidx.compose.ui.unit.Dp = 48.dp): ImageBitmap? {
    if (art == null || art.isEmpty()) return null
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetPx = with(density) { targetSize.roundToPx() }.coerceIn(1, 2048)
    // `remember` sobre el propio ByteArray: equals de un array es por
    // identidad (O(1)), no por contenido. Indexar por `art.size` reutilizaba
    // la clave de otra portada del mismo tamaño en celdas recicladas.
    val cacheKey = remember(art, targetPx) { ArtBitmapMemoryCache.key("px$targetPx", art) }
    return androidx.compose.runtime.key(cacheKey) {
        produceState(initialValue = ArtBitmapMemoryCache.get(cacheKey), cacheKey) {
            value = try {
                ArtBitmapMemoryCache.decode(art, targetPx)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }.value
    }
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
    /** null oculta "Añadir a playlist" del menú. */
    onAddToPlaylist: (() -> Unit)? = null,
    /** Dentro de una playlist: "Quitar de la playlist". */
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val aura = animatedAura()
    val auraSoft = EighthTheme.softAura(aura)
    val accent = EighthTheme.accent(aura)
    val art = rememberArtBitmap(song.albumArt, targetSize = 44.dp)

    Box(modifier.padding(bottom = 7.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .glassCard(active = isCurrent, accent = accent)
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
                    color = if (isCurrent) accent else EighthTheme.TextPrimary,
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
                GlassIconButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = null,
                    onClick = { menuOpen = true },
                )
                GlassDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    GlassMenuItem(
                        label = "Reproducir a continuación",
                        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        onClick = {
                            menuOpen = false
                            onAddToNext()
                        },
                    )
                    GlassMenuItem(
                        label = "Añadir al final de la cola",
                        icon = Icons.Rounded.Queue,
                        onClick = {
                            menuOpen = false
                            onAddToEnd()
                        },
                    )
                    if (onAddToPlaylist != null) {
                        GlassMenuItem(
                            label = "Añadir a playlist",
                            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            onClick = {
                                menuOpen = false
                                onAddToPlaylist()
                            },
                        )
                    }
                    if (onRemoveFromPlaylist != null) {
                        GlassMenuItem(
                            label = "Quitar de la playlist",
                            icon = Icons.Rounded.RemoveCircleOutline,
                            onClick = {
                                menuOpen = false
                                onRemoveFromPlaylist()
                            },
                        )
                    }
                }
            }
        }
    }
}
