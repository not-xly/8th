package com.xly.u_8th.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Fondo ambiental de la biblioteca: la carátula de la canción en curso,
 * muy ampliada y desenfocada, se funde hacia abajo con el fondo, y un halo
 * del aura asoma por arriba. Enlaza visualmente con el reproductor sin
 * competir con las portadas del grid.
 */
@Composable
fun LibraryBackdrop(art: ByteArray?, aura: Color, modifier: Modifier = Modifier) {
    val fade = remember {
        Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)
    }
    Box(modifier.fillMaxSize()) {
        val bitmap = rememberArtBitmap(art, targetSize = 48.dp)
        Crossfade(
            targetState = bitmap,
            animationSpec = tween(900),
            label = "libraryBackdrop",
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                // Máscara de alfa: la carátula se desvanece hacia abajo sin
                // depender del color exacto del fondo de debajo.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(fade, blendMode = BlendMode.DstIn)
                },
        ) { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.5f
                            scaleY = 1.5f
                            alpha = 0.34f
                        }
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(60.dp) else Modifier),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(aura.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.2f, 0f),
                    radius = size.width * 1.1f,
                ),
            )
        }
    }
}
