package com.xly.u_8th.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Obtiene la portada de un álbum a partir de sus pistas: primero el arte
 * embebido, después la portada que MediaStore ya resolvió (incluye carátulas
 * de carpeta indexadas por el sistema). Las imágenes grandes se reducen.
 */
class AlbumArtExtractor(private val context: Context) {

    /**
     * Primera portada encontrada entre [albumSongs], o null. [isStale] se
     * consulta entre pista y pista para abandonar trabajo obsoleto.
     */
    suspend fun extract(albumSongs: List<Song>, isStale: () -> Boolean): ByteArray? {
        for (s in albumSongs) {
            if (isStale()) return null
            extractEmbedded(s)?.let { return it }
        }
        for (s in albumSongs) {
            if (isStale()) return null
            extractAlbumArtUri(s)?.let { return it }
        }
        return null
    }

    private suspend fun extractEmbedded(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        // El retriever sobre content:// falla en algunos fabricantes: el
        // path directo (cuando MediaStore lo da y existe) es el reintento.
        embeddedArt(song.playUri, song.isContentUri)
            ?: song.filePath?.takeIf { it.isNotEmpty() && File(it).exists() }?.let {
                embeddedArt(it, content = false)
            }
    }

    private fun embeddedArt(source: String, content: Boolean): ByteArray? =
        try {
            val retriever = MediaMetadataRetriever()
            try {
                if (content) {
                    retriever.setDataSource(context, source.toUri())
                } else {
                    retriever.setDataSource(source)
                }
                retriever.embeddedPicture?.let(::boundedArt)
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            null
        }

    /** Portada de MediaStore del álbum (content://media/external/audio/albumart). */
    private suspend fun extractAlbumArtUri(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val uri = song.albumArtUri?.takeIf { it.isNotEmpty() } ?: return@withContext null
        try {
            context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }?.let(::boundedArt)
        } catch (_: Exception) {
            null
        }
    }

    private fun boundedArt(data: ByteArray): ByteArray? = when {
        data.isEmpty() -> null
        data.size <= MAX_ART_BYTES -> data
        else -> downscaleJpeg(data)
    }

    /** >300KB: downscale + JPEG 85. */
    private fun downscaleJpeg(data: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val MAX_ART_BYTES = 300 * 1024
    }
}
