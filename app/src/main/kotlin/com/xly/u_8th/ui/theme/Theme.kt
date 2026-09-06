package com.xly.u_8th.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xly.u_8th.util.fnv1aBytesSampled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.delay

/**
 * "Liquid glass" dark theme tokens, ported from the previous app's
 * AppTheme. Blur is limited to fixed chrome in the original; here chrome
 * uses the same translucent fills without true backdrop blur (the
 * background is a near-solid dark gradient, so the visual result matches).
 */
object EighthTheme {
    val Background = Color(0xFF09090B) // zinc-950, OLED black
    val BackgroundGradientTop = Color(0xFF0F0F12)
    val BackgroundGradientBottom = Color(0xFF09090B)

    val GlassSurface = Color(0xFF1C1C1E) // iOS dark surface
    val GlassSurfaceLight = Color(0xFF2C2C2E) // iOS secondary
    val GlassBorder = Color(0x0FFFFFFF) // white 6%
    val GlassBorderStrong = Color(0x14FFFFFF) // white 8%

    val Primary = Color(0xFFFF3B30) // default aura
    val PrimarySoft = Color(0xFFFF6B6B)
    val TextPrimary = Color(0xFFF5F5F7) // Apple off-white
    val TextSecondary = Color(0xFF8E8E93) // iOS secondary label
    val TextTertiary = Color(0xFF636366) // iOS tertiary

    val SelectedCard = Color(0xFF2A1D1E)

    val RadiusXL = 26.dp
    val RadiusL = 18.dp
    val RadiusM = 14.dp
    val RadiusS = 10.dp

    val EasingStandard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f) // easeInOutCubic-ish
    val EasingOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
    val EasingInCubic = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)
    val EasingOutBack = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)

    /** Aura color with reduced saturation, for selected text/icons. */
    fun desaturated(color: Color, amount: Float = 0.45f): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (color.alpha * 255).toInt(), (color.red * 255).toInt(),
                (color.green * 255).toInt(), (color.blue * 255).toInt(),
            ),
            hsv,
        )
        hsv[1] = (hsv[1] * (1 - amount)).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Blend of soft primary and aura (0 = primarySoft, 1 = aura). */
    fun softAura(aura: Color, t: Float = 0.6f): Color = lerpColor(PrimarySoft, aura, t)
}

fun lerpColor(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = from.alpha + (to.alpha - from.alpha) * t,
)

/**
 * Global palette service: extracts the accent color from the current
 * song's album art in the background, with an LRU cache keyed by sampled
 * content hash. UI animates toward [aura] with ~440ms.
 */
object AuraPalette {

    private const val FALLBACK = 0xFFFF3B30

    private val _aura = MutableStateFlow(Color(FALLBACK))
    val aura: StateFlow<Color> = _aura

    private val cache = LinkedHashMap<Long, Color>()

    private fun cacheGet(hash: Long): Color? = synchronized(cache) {
        cache.remove(hash)?.also { cache[hash] = it }
    }

    private fun cachePut(hash: Long, c: Color) = synchronized(cache) {
        cache[hash] = c
        while (cache.size > 60) cache.remove(cache.keys.first())
    }

    private var lastHash = 0L
    private var hasLast = false

    /** Called when the current song's art becomes available or changes. */
    suspend fun updateForArt(art: ByteArray?) {
        if (art == null || art.isEmpty()) {
            hasLast = false
            lastHash = 0
            setAuraTarget(Color(FALLBACK))
            return
        }
        val hash = fnv1aBytesSampled(art)
        if (hasLast && hash == lastHash) return
        hasLast = true
        lastHash = hash
        cacheGet(hash)?.let {
            setAuraTarget(it)
            return
        }
        try {
            // Small delay so the navigation transition does not compete
            // with the decode, then a tiny palette for speed.
            delay(80)
            if (!hasLast || hash != lastHash) return
            val color = withContext(Dispatchers.Default) {
                val opts = BitmapFactory.Options().apply { inSampleSize = computeSample(art) }
                val bmp = BitmapFactory.decodeByteArray(art, 0, art.size, opts) ?: return@withContext null
                val palette = Palette.from(bmp).maximumColorCount(8).clearFilters().generate()
                bmp.recycle()
                palette.vibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb?.let { soften(it) }
            } ?: return setAuraTarget(Color(FALLBACK))
            val c = Color(color).copy(alpha = 1f)
            cachePut(hash, c)
            if (hasLast && hash == lastHash) setAuraTarget(c)
        } catch (_: Exception) {
            setAuraTarget(Color(FALLBACK))
        }
    }

    /** Dominant swatches tend to be muddy; lift saturation slightly. */
    private fun soften(rgb: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        hsv[1] = (hsv[1] * 1.25f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 1.05f).coerceIn(0f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun computeSample(art: ByteArray): Int = 8

    /** Animated target so color changes glide (~440ms) like the original. */
    /** The UI animates toward this value; here we just publish the target. */
    private fun setAuraTarget(target: Color) {
        _aura.value = target
    }

    fun reset() {
        hasLast = false
        lastHash = 0
        _aura.value = Color(FALLBACK)
    }
}

/** Compose helper: collect the aura flow as an animated color. */
@Composable
fun animatedAura(): Color {
    val aura by AuraPalette.aura.collectAsState()
    return animateColorAsState(
        targetValue = aura,
        animationSpec = tween(durationMillis = 440, easing = EighthTheme.EasingStandard),
        label = "aura",
    ).value
}
