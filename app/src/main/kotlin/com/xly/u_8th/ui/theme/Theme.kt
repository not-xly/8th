package com.xly.u_8th.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xly.u_8th.util.fnv1aBytesSampled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.delay

/**
 * "Liquid glass" dark theme tokens. Chrome uses translucent fills without true backdrop blur (the
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
    val Danger = Color(0xFFFF6B6B)

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

    // --- Un único "cristal" para toda la app -------------------------------
    // Relleno en degradado vertical (brillo arriba) y filo fino que se
    // desvanece hacia abajo. Lo usan botones, cápsulas, pastillas y menús.
    val GlassFillTop = Color.White.copy(alpha = 0.12f)
    val GlassFillBottom = Color.White.copy(alpha = 0.05f)
    val GlassStrokeTop = Color.White.copy(alpha = 0.20f)
    val GlassStrokeBottom = Color.White.copy(alpha = 0.05f)
    val GlassPressed = Color.White.copy(alpha = 0.14f)
    val GlassStrokeWidth = 0.8.dp

    /** Fondo opaco de menús, diálogos y hojas. */
    val PopupSurface = Color(0xFF202023)

    // Tamaños de botón de icono: compacto (filas, letras), estándar
    // (cabeceras de hojas, cápsula) y grande (reproductor).
    val IconButtonS = 34.dp
    val IconButtonM = 40.dp
    val IconButtonL = 44.dp

    /**
     * Acento legible sobre fondo oscuro. El aura sale de la portada y a
     * veces es gris o muy oscura: entonces se mezcla con el rojo de la marca
     * y se le sube el brillo, para que "activo" siempre se distinga.
     */
    fun accent(aura: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb((aura.red * 255).toInt(), (aura.green * 255).toInt(), (aura.blue * 255).toInt()),
            hsv,
        )
        val base = if (hsv[1] < 0.28f) lerpColor(aura, PrimarySoft, 0.7f) else aura
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb((base.red * 255).toInt(), (base.green * 255).toInt(), (base.blue * 255).toInt()),
            hsv,
        )
        hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
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

    /**
     * `lastHash`/`hasLast` se leen y escriben desde varias coroutines
     * independientes (`MainViewModel` las lanza al cambiar de canción y al
     * llegar portada), así que el trío mutable va bajo el mismo lock: sin
     * él dos decodes concurrentes podían publicar ambos y dejar el estado
     * "sin hash" con un hash no nulo (aura bloqueada en el fallback).
     */
    private val stateLock = Any()

    /** Reclamo atómico: devuelve false si este hash ya se está procesando. */
    private fun claim(hash: Long): Boolean = synchronized(stateLock) {
        if (hasLast && hash == lastHash) {
            false
        } else {
            hasLast = true
            lastHash = hash
            true
        }
    }

    private fun isCurrent(hash: Long): Boolean = synchronized(stateLock) {
        hasLast && hash == lastHash
    }

    private fun forget() = synchronized(stateLock) {
        hasLast = false
        lastHash = 0L
    }

    private var lastHash = 0L
    private var hasLast = false

    /** Called when the current song's art becomes available or changes. */
    suspend fun updateForArt(art: ByteArray?) {
        if (art == null || art.isEmpty()) {
            forget()
            setAuraTarget(Color(FALLBACK))
            return
        }
        val hash = fnv1aBytesSampled(art)
        if (!claim(hash)) return
        cacheGet(hash)?.let {
            setAuraTarget(it)
            return
        }
        try {
            // Small delay so the navigation transition does not compete
            // with the decode, then a tiny palette for speed.
            delay(80)
            if (!isCurrent(hash)) return
            val color = withContext(Dispatchers.Default) {
                // Muestreo calculado desde las dimensiones reales: un
                // inSampleSize fijo de 8 descartaba hasta 64x de los píxeles
                // incluso para una miniatura de 40 dp.
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
                var sample = 1
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                // El palette solo necesita ~128px: por encima de eso el
                // coste de decodificar crece sin mejorar el color.
                while (maxSide / sample > AURA_TARGET_PX * 2) sample *= 2
                opts.inSampleSize = sample
                val bmp = BitmapFactory.decodeByteArray(art, 0, art.size, opts) ?: return@withContext null
                val palette = Palette.from(bmp).maximumColorCount(8).clearFilters().generate()
                bmp.recycle()
                palette.vibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb?.let { soften(it) }
            } ?: return setAuraTarget(Color(FALLBACK))
            val c = Color(color).copy(alpha = 1f)
            cachePut(hash, c)
            if (isCurrent(hash)) setAuraTarget(c)
        } catch (e: CancellationException) {
            // CancellationException es un IllegalStateException: sin esto el
            // "cancelar" se convertía en un aura de fallback publicada.
            throw e
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

    /** Lado máximo en píxeles que necesita el extractor de paleta. */
    private const val AURA_TARGET_PX = 128

    /** Animated target so color changes glide (~440ms) like the original;
     *  the UI animates toward it, here we only publish the target. */
    private fun setAuraTarget(target: Color) {
        _aura.value = target
    }
}

/** Compose helper: collect the aura flow as an animated color. */
@Composable
fun animatedAura(): Color = LocalAnimatedAura.current.value

private val LocalAnimatedAura = compositionLocalOf<State<Color>> { mutableStateOf(EighthTheme.Primary) }

@Composable
fun AuraProvider(content: @Composable () -> Unit) {
    val aura by AuraPalette.aura.collectAsStateWithLifecycle()
    val animated = animateColorAsState(
        targetValue = aura,
        animationSpec = tween(durationMillis = 440, easing = EighthTheme.EasingStandard),
        label = "aura",
    )
    CompositionLocalProvider(LocalAnimatedAura provides animated, content = content)
}
