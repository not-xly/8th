package com.xly.u_8th.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.xly.u_8th.domain.Song
import com.xly.u_8th.domain.parseMetadata
import com.xly.u_8th.util.audioExtensions
import kotlinx.coroutines.Dispatchers
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
 * Library loading: MediaStore/SAF listing and metadata parsing, ported from
 * library_service.dart plus the native channel that lived in the old
 * Flutter-era MainActivity.
 *
 * [context]/[prefs] are nullable only so JVM unit tests can exercise the
 * pure conversion ([songsFromMedia]) without an Android Context; production
 * callers always pass both.
 */
open class LibraryRepository(context: Context?, prefs: Prefs?) {

    private val appContext: Context? = context?.applicationContext
    private val prefs: Prefs? = prefs

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
     * Recursive scan (the Dart fallback for when MediaStore/DATA fails under
     * scoped storage). Symlinks are not followed (walkTopDown default): a
     * symlink loop would hang the walk. Sorted alphabetically, like Dart.
     */
    suspend fun scanDirectoryForAudio(dirPath: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(dirPath)
            if (!dir.isDirectory) return@runCatching emptyList<String>()
            dir.walkTopDown()
                .onFail { _, _ -> }
                .filter { it.isFile && it.extension.lowercase() in audioExtensions }
                .map { it.path }
                .toList()
                .sorted()
        }.getOrDefault(emptyList())
    }

    // ---- Folder listing ----

    /**
     * Port of controller.loadFromTreeUri without the interactive fallback:
     * MediaStore first for resolvable folder paths, SAF for content://
     * trees, null when nothing loads (the UI decides what comes next).
     */
    suspend fun loadFromTreeUri(treeUri: String): LoadResult = withContext(Dispatchers.IO) {
        val folderPath = resolveFolderPath(treeUri)
        if (folderPath != null) {
            val fromStore = runCatching { listFromMediaStore(folderPath) }.getOrDefault(emptyList())
            if (fromStore.isNotEmpty()) {
                return@withContext LoadResult(songsFromMedia(fromStore), folderScanned = false)
            }
            // MediaStore empty (scoped storage): SAF only for content://
            // trees; for filesystem paths the direct scan is the fallback.
            if (treeUri.startsWith("content://")) {
                val fromSaf = runCatching { listFromSAF(treeUri) }.getOrDefault(emptyList())
                if (fromSaf.isNotEmpty()) {
                    return@withContext LoadResult(songsFromMedia(fromSaf), folderScanned = false)
                }
            }
            LoadResult(null, folderScanned = isScannableDir(folderPath))
        } else {
            val fromSaf = runCatching { listFromSAF(treeUri) }.getOrDefault(emptyList())
            if (fromSaf.isNotEmpty()) {
                LoadResult(songsFromMedia(fromSaf), folderScanned = false)
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
     */
    private fun resolveFolderPath(treeUri: String): String? = try {
        when {
            treeUri.startsWith("/") -> treeUri
            treeUri.startsWith("file://") -> Uri.parse(treeUri).path
            else -> {
                val uri = Uri.parse(treeUri)
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

    private fun listFromMediaStore(folderPath: String): List<SongMedia> {
        val resolver = appContext?.contentResolver ?: return emptyList()
        val results = mutableListOf<SongMedia>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // DATA is deprecated since Android 10 and often null on 11+ (scoped
        // storage): RELATIVE_PATH is requested when available and filtering
        // happens in code instead of "DATA LIKE path%" (which returned empty
        // and fell back to the picker on every start).
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        var relativeColName: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            relativeColName = MediaStore.Audio.Media.RELATIVE_PATH
            projection.add(relativeColName)
        }

        val normalizedPath = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
        // Expected relative prefix: "/storage/emulated/0/Music/Sub" -> "Music/Sub/"
        val expectedRelative = normalizedPath
            .removePrefix("/storage/emulated/0/")
            .trim('/')
            .let { if (it.isEmpty()) "" else "$it/" }
        val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        // IS_MUSIC=1: without it ringtones/recordings leak in.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        // Same anti-TransactionTooLarge cap as the SAF route.
        val cap = 2000

        resolver.query(collection, projection.toTypedArray(), selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val relCol = relativeColName?.let { cursor.getColumnIndex(it) } ?: -1

            while (cursor.moveToNext() && results.size < cap) {
                val id = cursor.getLong(idCol)
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                val displayName = if (nameCol >= 0) cursor.getString(nameCol) else null
                val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                val ext = (displayName ?: data ?: "").substringAfterLast('.', "").lowercase()
                if (ext !in audioExtensions) continue

                // OR, not exclusive: DATA can point at a different FUSE path
                // (/sdcard vs /storage/emulated/0) while RELATIVE_PATH still
                // matches — an AND here emptied whole folders.
                val dataMatch = !data.isNullOrEmpty() &&
                    (data.startsWith(normalizedPath) || data == folderPath)
                val relMatch = !relPath.isNullOrEmpty() && expectedRelative.isNotEmpty() &&
                    (relPath.startsWith(expectedRelative) || relPath.trim('/') == expectedRelative.trim('/'))
                val inFolder = dataMatch || relMatch || expectedRelative.isEmpty()
                if (!inFolder) continue

                val albumId = if (albumIdCol >= 0 && !cursor.isNull(albumIdCol)) cursor.getLong(albumIdCol) else null

                results.add(
                    SongMedia(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        name = displayName ?: "",
                        title = if (titleCol >= 0) cursor.getString(titleCol) else null,
                        artist = if (artistCol >= 0) cursor.getString(artistCol) else null,
                        album = if (albumCol >= 0) cursor.getString(albumCol) else null,
                        track = if (trackCol >= 0) cursor.getInt(trackCol) else 0,
                        duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                        // Cover URI derivable from the row's own albumId —
                        // cheaper than querying the Albums table per row.
                        artUri = albumId?.let {
                            ContentUris.withAppendedId(
                                Uri.parse("content://media/external/audio/albumart"),
                                it,
                            ).toString()
                        },
                        path = data,
                    )
                )
            }
        }
        return results
    }

    private fun listFromSAF(treeUri: String): List<SongMedia> {
        val resolver = appContext?.contentResolver ?: return emptyList()
        val uri = Uri.parse(treeUri)
        try {
            // Persistable permission budget is limited: take it only once.
            val alreadyPersisted = resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!alreadyPersisted) {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
        }

        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val result = mutableListOf<SongMedia>()
        val cap = 2000
        // BFS with ONE cursor per directory: DocumentFile.listFiles() queries
        // the provider PER CHILD (N+1) and took seconds on big trees.
        data class Dir(val id: String, val depth: Int)

        val queue = ArrayDeque<Dir>()
        queue.add(Dir(rootDocId, 0))

        while (queue.isNotEmpty() && result.size < cap) {
            val dir = queue.removeFirst()
            if (dir.depth > 12) continue
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, dir.id)
            } catch (_: Exception) {
                continue
            }
            val subDirs = mutableListOf<String>()
            try {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext() && result.size < cap) {
                        val docId = cursor.getString(idCol) ?: continue
                        val name = cursor.getString(nameCol) ?: continue
                        val mime = cursor.getString(mimeCol)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            subDirs.add(docId)
                        } else {
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (ext in audioExtensions) {
                                result.add(
                                    SongMedia(
                                        uri = DocumentsContract.buildDocumentUriUsingTree(uri, docId).toString(),
                                        name = name,
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Directory without permission: skip its children, keep scanning.
            }
            for (id in subDirs) queue.add(Dir(id, dir.depth + 1))
        }
        return result
    }

    // ---- Conversions / metadata ----

    /**
     * Port of fromRichList: native rows -> Songs. Drops rows without uri;
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
        Song(
            path = uri,
            contentUri = uri,
            filePath = m.path,
            title = m.title ?: name.replace(Regex("\\.[^.]+$"), ""),
            artist = MetadataFixes.artistFor(album = m.album, artist = m.artist),
            album = m.album,
            trackNumber = track,
            durationMs = m.duration.takeIf { it > 0 },
            albumArtUri = m.artUri?.takeIf { it.isNotEmpty() },
            albumArt = null,
        )
    }

    /**
     * Port of parseMetadata (song_data.dart): file paths are filtered by
     * extension and parsed off-thread; content:// URIs also get their tags
     * read here (the picker fallback saves URIs, not paths, so without this
     * those libraries would show no artist/album/duration). Missing or
     * unreadable entries keep the bare Song (no ghost entries).
     */
    suspend fun parseMetadataForPaths(paths: List<String>): List<Song> = withContext(Dispatchers.IO) {
        paths.mapNotNull { path ->
            if (path.startsWith("content://")) {
                readSongMetadataForUri(path) ?: Song(path = path, contentUri = path)
            } else {
                parseMetadata(listOf(path)) { readSongMetadata(it) }.firstOrNull()
            }
        }
    }

    /**
     * Metadata for one content:// URI; null when unreadable. Open so
     * tests/fakes can stub the per-URI IO.
     */
    open fun readSongMetadataForUri(uriString: String): Song? {
        val ctx = appContext ?: return null
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, Uri.parse(uriString))
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
        } catch (_: Exception) {
            return null
        }
    }

    // ---- Cold-start restore ----

    /**
     * Songs from the saved treeUri or filePaths (after the Flutter prefs
     * migration), without interactive fallbacks. Null when nothing is
     * configured or nothing loads.
     */
    suspend fun restoreSongs(): List<Song>? = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext null
        p.migrateFromFlutterIfNeeded()
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
