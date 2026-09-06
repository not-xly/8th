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
            for (candidate in listOf(filePath, path)) {
                if (candidate.isNullOrEmpty()) continue
                if (candidate.startsWith("content://")) continue
                val base = candidate.substringAfterLast('/').replace(Regex("\\.[^.]+$"), "")
                if (base.isNotEmpty() && base.toLongOrNull() == null) return base
            }
            val fallback = path.substringAfterLast('/').replace(Regex("\\.[^.]+$"), "")
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
            !filePath.isNullOrEmpty() && !filePath.startsWith("content://") -> "file://$filePath"
            path.startsWith("content://") -> path
            else -> "file://$path"
        }

    /** Stable id for caches/heroes: prefers contentUri. */
    val stableId: String get() = playUri

    override fun equals(other: Any?): Boolean = other is Song && other.path == path
    override fun hashCode(): Int = path.hashCode()

    companion object {
        /**
         * Stable key per queue entry: allows duplicates (same song twice in
         * the queue) without depending on the index. The previous-occurrence
         * count keeps each repeat's key stable even when its absolute
         * position changes on reorder.
         */
        fun queueEntryKey(list: List<Song>, index: Int): String {
            val uri = list[index].playUri
            var occurrence = 0
            for (i in 0 until index) {
                if (list[i].playUri == uri) occurrence++
            }
            return "$uri#$occurrence"
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
