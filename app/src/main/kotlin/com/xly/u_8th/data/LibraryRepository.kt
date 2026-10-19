package com.xly.u_8th.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.xly.u_8th.domain.Song
import com.xly.u_8th.domain.parseMetadata
import com.xly.u_8th.util.audioExtensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One row as MediaStore/SAF returned it, before conversion to [Song].
 * [track] keeps the raw row value (Int from MediaStore, "3/12" from legacy
 * channel maps) so the conversion stays pure and unit-testable on the JVM.
 */
data class SongMedia(
    val uri: String,
    val name: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val track: Any? = 0,
    val duration: Long = 0,
    val artUri: String? = null,
    val path: String? = null,
)

/**
 * Result of a native folder listing. [songs] == null means MediaStore/SAF
 * came back empty or failed; the UI decides the fallback. [folderScanned]
 * (only meaningful when songs == null) says the tree resolved to a real
 * filesystem folder, so scanDirectoryForAudio — the old controller's
 * fallback for "/" trees — is the sensible next step; otherwise fall back
 * to the file picker.
 */
data class LoadResult(val songs: List<Song>?, val folderScanned: Boolean)

/**
 * Library loading: MediaStore/SAF listing and metadata parsing.
 *
 * [context]/[prefs] are nullable only so JVM unit tests can exercise the
 * pure conversion ([songsFromMedia]) without an Android Context; production
 * callers always pass both.
 */
open class LibraryRepository(context: Context?, prefs: Prefs?) {

    private val appContext: Context? = context?.applicationContext
    private val prefs: Prefs? = prefs
    private val metadataPermits = Semaphore(4)
    private val scanner by lazy { MediaScanner(appContext?.contentResolver) }

    private inline fun <T> recover(block: () -> T, fallback: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback()
    }

    private suspend fun <T, R> mapMetadata(items: List<T>, read: suspend (T) -> R): List<R> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<R>(items.size)
            for (batch in items.chunked(4)) {
                currentCoroutineContext().ensureActive()
                result.addAll(coroutineScope {
                    batch.map { item ->
                        async {
                            metadataPermits.withPermit {
                                currentCoroutineContext().ensureActive()
                                val value = read(item)
                                currentCoroutineContext().ensureActive()
                                value
                            }
                        }
                    }.awaitAll()
                })
            }
            result
        }

    // ---- Permissions (no Activity here: the UI layer requests) ----

    /** Storage permission to request from the UI, API-dependent. */
    fun audioPermissionString(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasAudioPermission(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, audioPermissionString()) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ---- Direct scan ----

    /**
     * Recursive scan (the direct-scan fallback for when MediaStore/DATA fails under
     * scoped storage). Symlinks are not followed (walkTopDown default): a
     * symlink loop would hang the walk. Sorted alphabetically, alphabetical.
     */
    suspend fun scanDirectoryForAudio(dirPath: String): List<String> = withContext(Dispatchers.IO) {
        val pending = ArrayDeque<File>()
        val visited = HashSet<String>()
        val results = mutableListOf<String>()
        pending.add(File(dirPath))
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = pending.removeLast()
            val canonical = recover({ dir.canonicalPath }, { null }) ?: continue
            if (!visited.add(canonical)) continue
            val children = recover({ dir.listFiles() }, { null }) ?: continue
            for (file in children) {
                currentCoroutineContext().ensureActive()
                recover({
                    if (file.isDirectory) pending.add(file)
                    else if (file.isFile && file.extension.lowercase() in audioExtensions) results.add(file.path)
                }, { Unit })
            }
        }
        results.sorted()
    }

    // ---- Folder listing ----

    /**
     * Loads a saved folder without interactive fallbacks:
     * MediaStore first for resolvable folder paths, SAF for content://
     * trees, null when nothing loads (the UI decides what comes next).
     */
    suspend fun loadFromTreeUri(treeUri: String): LoadResult = withContext(Dispatchers.IO) {
        val folderPath = resolveFolderPath(treeUri)
        if (folderPath != null) {
            val fromStore = recover({ scanner.fromMediaStore(folderPath) }, { emptyList() })
            if (fromStore.isNotEmpty()) {
                return@withContext LoadResult(songsFromMedia(fromStore), folderScanned = false)
            }
            // MediaStore empty (scoped storage): SAF only for content://
            // trees; for filesystem paths the direct scan is the fallback.
            if (treeUri.startsWith("content://")) {
                val fromSaf = recover({ scanner.fromSaf(treeUri) }, { emptyList() })
                if (fromSaf.isNotEmpty()) {
                    return@withContext LoadResult(songsFromSaf(fromSaf), folderScanned = false)
                }
            }
            LoadResult(null, folderScanned = isScannableDir(folderPath))
        } else {
            val fromSaf = recover({ scanner.fromSaf(treeUri) }, { emptyList() })
            if (fromSaf.isNotEmpty()) {
                LoadResult(songsFromSaf(fromSaf), folderScanned = false)
            } else {
                LoadResult(null, folderScanned = false)
            }
        }
    }

    private fun isScannableDir(path: String?): Boolean =
        path != null && runCatching { File(path).isDirectory }.getOrDefault(false)

    /**
     * Maps a treeUri to a real folder path when possible: filesystem paths
     * and file:// URIs pass through; content:// trees only when they point
     * at primary storage ("primary:Music" -> /storage/emulated/0/Music).
     *
     * Public because the ViewModel needs the same mapping to decide whether
     * a direct filesystem scan is a sensible fallback; it used to keep a
     * duplicated copy that could drift.
     */
    fun resolveFolderPath(treeUri: String): String? = try {
        when {
            treeUri.startsWith("/") -> treeUri
            treeUri.startsWith("file://") -> treeUri.toUri().path
            else -> {
                val uri = treeUri.toUri()
                val id = if (uri.scheme == "content") {
                    try {
                        DocumentsContract.getTreeDocumentId(uri)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                if (id != null && id.startsWith("primary:")) {
                    "/storage/emulated/0/" + id.removePrefix("primary:")
                } else {
                    null
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    // ---- Conversions / metadata ----

    /**
     * Rows -> Songs. Drops rows without uri;
     * title falls back to the name minus extension; track "3/12" -> 3 and
     * 0/"" -> null; duration kept only when positive.
     */
    suspend fun songsFromMedia(list: List<SongMedia>): List<Song> = list.mapNotNull { m ->
        val uri = m.uri
        if (uri.isEmpty()) return@mapNotNull null
        val name = m.name.ifEmpty { uri.substringAfterLast('/') }
        val track = when (val t = m.track) {
            is Number -> if (t.toLong() > 0) t.toInt() else null
            is String -> t.substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }
        // MediaStore fills "<unknown>" into untagged tags: show the app's
        // "Desconocido" instead of leaking the literal placeholder.
        fun cleanTag(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
        val album = cleanTag(m.album)
        Song(
            path = uri,
            contentUri = uri,
            filePath = m.path,
            title = m.title ?: name.replace(Regex("\\.[^.]+$"), ""),
            artist = MetadataFixes.artistFor(album = album, artist = cleanTag(m.artist)),
            album = album,
            trackNumber = track,
            durationMs = m.duration.takeIf { it > 0 },
            albumArtUri = m.artUri?.takeIf { it.isNotEmpty() },
            albumArt = null,
        )
    }

    /**
     * Metadata parsing: file paths are filtered by
     * extension and parsed off-thread; content:// URIs also get their tags
     * read here (the picker fallback saves URIs, not paths, so without this
     * those libraries would show no artist/album/duration). Missing or
     * unreadable entries keep the bare Song (no ghost entries).
     */
    suspend fun parseMetadataForPaths(paths: List<String>): List<Song> = mapMetadata(paths) { path ->
        if (path.startsWith("content://")) {
            readSongMetadataForUri(path) ?: Song(path = path, contentUri = path)
        } else {
            parseMetadata(listOf(path)) { readSongMetadata(it) }.firstOrNull()
        }
    }.filterNotNull()

    internal suspend fun songsFromSaf(rows: List<SongMedia>): List<Song> = mapMetadata(rows) { row ->
        val fallback = songsFromMedia(listOf(row)).firstOrNull() ?: return@mapMetadata null
        val metadata = readSongMetadataForUri(row.uri) ?: return@mapMetadata fallback
        metadata.copy(
            path = row.uri,
            contentUri = row.uri,
            title = metadata.title?.takeIf { it.isNotBlank() } ?: fallback.title,
        )
    }.filterNotNull()

    /**
     * Metadata for one content:// URI; null when unreadable. Open so
     * tests/fakes can stub the per-URI IO.
     */
    open fun readSongMetadataForUri(uriString: String): Song? {
        val ctx = appContext ?: return null
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, uriString.toUri())
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val trackRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                val durationRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val name = uriString.substringAfterLast('/')
                    .substringBefore('?')
                    .replace(Regex("\\.[^.]+$"), "")
                return Song(
                    path = uriString,
                    contentUri = uriString,
                    title = title ?: name,
                    artist = MetadataFixes.artistFor(album = album, artist = artist),
                    album = album,
                    trackNumber = trackRaw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                    durationMs = durationRaw?.toLongOrNull()?.takeIf { it > 0 },
                    albumArt = null,
                )
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Reads one file's metadata; null when missing or unreadable. Open so
     * tests/fakes can stub the per-file IO.
     */
    open fun readSongMetadata(path: String): Song? {
        try {
            if (!File(path).exists()) return null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val trackRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                val durationRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                return Song(
                    path = path,
                    title = title,
                    artist = MetadataFixes.artistFor(album = album, artist = artist),
                    album = album,
                    trackNumber = trackRaw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                    durationMs = durationRaw?.toLongOrNull()?.takeIf { it > 0 },
                    albumArt = null,
                )
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    // ---- Cold-start restore ----

    /**
     * Songs from the saved treeUri or filePaths (after the legacy prefs
     * migration), without interactive fallbacks. Null when nothing is
     * configured or nothing loads.
     */
    suspend fun restoreSongs(): List<Song>? = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext null
        p.migrateLegacyPrefsIfNeeded()
        val treeUri = p.getTreeUri()
        if (!treeUri.isNullOrEmpty()) {
            loadFromTreeUri(treeUri).songs?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        val paths = p.getFilePaths()
        if (!paths.isNullOrEmpty()) {
            parseMetadataForPaths(paths).takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        null
    }
}
