package com.xly.u_8th.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.xly.u_8th.util.fnv1aBytesSampled

/**
 * Caché de memoria (LRU) de portadas ya decodificadas, compartida por toda
 * la app. Al reciclarse una celda fuera de viewport el bitmap decodificado
 * sobrevive: volver a mostrar el álbum es instantáneo, sin re-decode ni
 * flash del placeholder. La clave es por CONTENIDO (tamaño + hash
 * muestreado), no por identidad del ByteArray.
 */
internal object ArtBitmapMemoryCache {

    /** ~1/8 del heap máximo, con suelo y techo razonables. */
    private val maxBytes =
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(12L * 1024 * 1024, 48L * 1024 * 1024)

    private val cache = object : LruCache<String, ImageBitmap>(maxBytes.toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().allocationByteCount
    }

    /** [variant] separa decodificados distintos de los mismos bytes (grid vs player). */
    fun key(variant: String, art: ByteArray): String =
        "$variant:${art.size}:${fnv1aBytesSampled(art)}"

    /**
     * Claves de todas las portadas de una lista, por orden. Evita recalcular
     * el hash muestreado una vez por elemento y por recomposición.
     */
    fun keys(variant: String, arts: List<ByteArray>): List<String> =
        arts.map { key(variant, it) }

    fun get(key: String): ImageBitmap? = cache.get(key)

    private val decodeLocks = Array(16) { Mutex() }

    suspend fun decode(art: ByteArray, targetPx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        val target = targetPx.coerceIn(1, 2048)
        val key = key("px$target", art)
        decodeLocks[(key.hashCode() and Int.MAX_VALUE) % decodeLocks.size].withLock {
            cache.get(key)?.let { return@withLock it }
            ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(art, 0, art.size, options) ?: return@withLock null
            val scale = minOf(1f, target.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) {
                decoded.scale(
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                ).also { if (it !== decoded) decoded.recycle() }
            } else decoded
            bitmap.asImageBitmap().also { cache.put(key, it) }
        }
    }
}
