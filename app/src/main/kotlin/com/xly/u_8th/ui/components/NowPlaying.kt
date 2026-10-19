package com.xly.u_8th.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlaybackCatalog
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.playback.withCatalogArt
import com.xly.u_8th.ui.theme.EighthTheme

/**
 * [withCatalogArt] reactivo: se recalcula cuando el catálogo cambia (la
 * biblioteca y sus portadas llegan después de restaurar la cola).
 */
@Composable
fun Song.rememberWithCatalogArt(): Song {
    if (albumArt != null) return this
    val revision by PlaybackCatalog.revision.collectAsStateWithLifecycle()
    return remember(this, revision) { withCatalogArt() }
}

/**
 * Tres barras de ecualizador: oscilan mientras suena y se recogen en pausa.
 * Lee [PlayerBus.playing] aquí dentro para que el resto de la fila no
 * recomponga al pausar.
 */
@Composable
fun EqualizerBars(color: Color, modifier: Modifier = Modifier) {
    val playing by PlayerBus.playing.collectAsStateWithLifecycle()
    val transition = rememberInfiniteTransition(label = "eq")
    val periods = intArrayOf(520, 380, 640)
    val phases = periods.mapIndexed { i, period ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period, delayMillis = i * 90), RepeatMode.Reverse),
            label = "eqBar$i",
        )
    }
    val amount by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "eqAmount")
    Canvas(modifier) {
        val gap = size.width * 0.16f
        val barW = (size.width - gap * 2) / 3f
        phases.forEachIndexed { i, phase ->
            val rest = 0.22f
            val h = size.height * (rest + (phase.value - rest) * amount).coerceIn(rest, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * Miniatura de carátula para filas de cola/listas. Si es la canción en curso
 * se oscurece y lleva encima las barras del ecualizador.
 */
@Composable
fun SongArtThumb(
    song: Song,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    radius: Dp = 9.dp,
) {
    val art = rememberArtBitmap(song.rememberWithCatalogArt().albumArt, targetSize = size)
    val dim by animateFloatAsState(if (isCurrent) 0.5f else 0f, tween(260), label = "thumbDim")
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(EighthTheme.GlassSurfaceLight),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = EighthTheme.TextTertiary, modifier = Modifier.size(size * 0.45f))
        }
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        if (isCurrent) EqualizerBars(Color.White, Modifier.size(size * 0.42f))
    }
}
