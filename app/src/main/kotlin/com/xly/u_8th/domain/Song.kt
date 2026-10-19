package com.xly.u_8th.domain

import android.os.Parcelable
import com.xly.u_8th.util.AppStrings
import com.xly.u_8th.util.audioExtensions
import kotlinx.parcelize.Parcelize

/**
 * A playable song. [path] is the identity used for stable hashing; when the
 * song comes from MediaStore/SAF, [contentUri] holds the playback URI and
 * [filePath] (when available) the real path.
 */
@Parcelize
data class Song(
    val path: String,
    val contentUri: String? = null,
    val filePath: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
    val albumArtUri: String? = null,
    val albumArt: ByteArray? = null,
) : Parcelable {

    val playUri: String
        get() = contentUri?.takeIf { it.isNotEmpty() } ?: path

    val displayTitle: String
        get() {
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            // For content:// URIs without metadata the last segment is a
            // numeric id (.../123): prefer filePath or the MediaStore name.
            val first = filePath
            if (!first.isNullOrEmpty() && !first.startsWith("content://")) {
                val base = first.baseNameNoExtension()
                if (base.isNotEmpty() && base.toLongOrNull() == null) return base
            }
            if (!path.startsWith("content://")) {
                val base = path.baseNameNoExtension()
                if (base.isNotEmpty() && base.toLongOrNull() == null) return base
            }
            val fallback = path.baseNameNoExtension()
            if (fallback.isEmpty() || fallback.toLongOrNull() != null) {
                return AppStrings.UNTITLED
            }
            return fallback
        }

    val isContentUri: Boolean
        get() = playUri.startsWith("content://")

    /**
     * Playback URI as string: contentUri first (stable against file moves),
     * then filePath, then path. Never touches disk.
     */
    val playbackUriString: String
        get() = when {
            isContentUri -> playUri
            !filePath.isNullOrEmpty() && !filePath.startsWith("content://") -> fileUri(filePath)
            path.startsWith("content://") -> path
            else -> fileUri(path)
        }

    /** Stable id for caches/heroes: prefers contentUri. */
    val stableId: String get() = playUri

    override fun equals(other: Any?): Boolean = other is Song && other.path == path
    override fun hashCode(): Int = path.hashCode()

    companion object {
        /**
         * `file://` URI con la ruta percent-encoded: sin escapar, un `#`, `?`
         * o `%` en el nombre se interpretaba como fragmento/query/escape y la
         * canción no se podía reproducir. Puro Kotlin (sin android.net.Uri)
         * para que funcione en tests JVM.
         */
        fun fileUri(path: String): String {
            val sb = StringBuilder(path.length + 16).append("file://")
            for (b in path.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val safe = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) || c.toChar() in "/-._~!$&'()*+,;=:@"
                if (safe) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                }
            }
            return sb.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        /** Compilada una vez: displayTitle se llama por fila visible. */
        private val EXTENSION = Regex("\\.[^.]+$")

        /** "a/b/c.ext" -> "c" (sin asignar lista intermedia). */
        private fun String.baseNameNoExtension(): String =
            substringAfterLast('/').replace(EXTENSION, "")

        /**
         * Stable key per queue entry: allows duplicates (same song twice in
         * the queue) without depending on the index. The previous-occurrence
         * count keeps each repeat's key stable even when its absolute
         * position changes on reorder.
         *
         * O(n) per call: use [queueEntryKeys] when the whole queue is needed
         * (LazyList invokes its key lambda for every item on every pass).
         */
        fun queueEntryKey(list: List<Song>, index: Int): String {
            val uri = list[index].playUri
            var occurrence = 0
            for (i in 0 until index) {
                if (list[i].playUri == uri) occurrence++
            }
            return "$uri#$occurrence"
        }

        /** [queueEntryKey] for every position, in one O(n) pass. */
        fun queueEntryKeys(list: List<Song>): List<String> {
            val counts = HashMap<String, Int>(list.size * 2)
            val keys = ArrayList<String>(list.size)
            for (song in list) {
                val uri = song.playUri
                val n = counts.getOrDefault(uri, 0)
                counts[uri] = n + 1
                keys.add("$uri#$n")
            }
            return keys
        }
    }
}

/**
 * Parses metadata for [paths] off the main thread. content:// URIs pass
 * through directly (they are not files); file paths are filtered by
 * extension and existence, and metadata is read where possible.
 */
fun parseMetadata(paths: List<String>, readMetadata: (String) -> Song?): List<Song> {
    val songs = mutableListOf<Song>()
    for (path in paths) {
        if (path.startsWith("content://")) {
            songs.add(Song(path = path, contentUri = path))
            continue
        }
        val ext = path.substringAfterLast('.').lowercase()
        if (ext !in audioExtensions) continue
        val song = readMetadata(path) ?: continue
        songs.add(song)
    }
    return songs
}
