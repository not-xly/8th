package com.xly.u_8th.ui.screens.fullplayer

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.playback.PlayerConnection
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.util.fnv1aBytesSampled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared building blocks for the full player and full lyrics screens
 * (glass 56dp app bar, gradient backdrop, album art decoder and the
 * velocity-based pan detector that drives skip/open/close flings).
 */

/** Vertical dark gradient used as the backdrop of both player screens. */
@Composable
fun GradientBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(EighthTheme.BackgroundGradientTop, EighthTheme.BackgroundGradientBottom),
                ),
            ),
        content = content,
    )
}

/**
 * 56dp translucent app bar with a bottom hairline, matching the Flutter
 * PreferredSize AppBar (glassSurface 42% + glassBorder hairline).
 */
@Composable
fun PlayerTopBar(
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(EighthTheme.GlassSurface.copy(alpha = 0.42f)),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { title() }
            if (trailing != null) trailing() else Spacer(Modifier.width(36.dp))
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.8.dp)
                .background(EighthTheme.GlassBorder),
        )
    }
}

/** Player-scoped connection looked up once per composition site. */
@Composable
fun rememberPlayerConnection(): PlayerConnection {
    val context = LocalContext.current
    return remember(context) { EighthApplication.get(context).playerConnection }
}

/**
 * Decodes [art] off the main thread, downsampling toward ~560px like the
 * Flutter `cacheWidth: 560`. Keyed by content (length + sampled hash) so
 * identical bytes never decode twice across recompositions.
 */
@Composable
fun rememberDecodedArt(art: ByteArray?): ImageBitmap? {
    val key = remember(art) { art?.let { it.size to fnv1aBytesSampled(it) } }
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key, art) {
        if (art == null || art.isEmpty()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 560) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(art, 0, art.size, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * Pan detector with a velocity decision, port of the full player's
 * onPanEnd. Changes consumed by children (slider, scrollables) cancel the
 * gesture so scrubbing/scrolling never triggers an action. [onEnd] receives
 * px/s velocities and the total drag distance in px.
 */
fun Modifier.playerPanDetector(
    onEnd: (vx: Float, vy: Float, totalDx: Float, totalDy: Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        var childConsumed = false
        var totalDx = 0f
        var totalDy = 0f
        var lastX = down.position.x
        var lastY = down.position.y
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) childConsumed = true
            val main = event.changes.firstOrNull { it.id == down.id }
            if (main != null) {
                tracker.addPosition(main.uptimeMillis, main.position)
                totalDx += main.position.x - lastX
                totalDy += main.position.y - lastY
                lastX = main.position.x
                lastY = main.position.y
            }
            if (event.changes.none { it.pressed }) break
        }
        if (childConsumed) return@awaitEachGesture
        val v = tracker.calculateVelocity()
        onEnd(v.x, v.y, totalDx, totalDy)
    }
}
