package com.xly.u_8th.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.xly.u_8th.util.audioExtensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Listado nativo de audio de una carpeta: MediaStore (filtrado por ruta) o
 * recorrido SAF del árbol. Devuelve filas crudas [SongMedia]; la conversión
 * a [Song][com.xly.u_8th.domain.Song] vive en [LibraryRepository].
 * Con [resolver] null (tests JVM) devuelve listas vacías.
 */
internal class MediaScanner(private val resolver: ContentResolver?) {

    suspend fun fromMediaStore(folderPath: String, filtered: Boolean = true): List<SongMedia> {
        val resolver = resolver ?: return emptyList()
        val results = mutableListOf<SongMedia>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

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

        val normalizedPath = folderPath.trimEnd('/') + "/"
        val expectedRelative = relativeFolderPath(folderPath)
        val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        val predicates = mutableListOf("${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'")
        val args = mutableListOf(escapeLike(normalizedPath) + "%")
        if (relativeColName != null && expectedRelative != null) {
            predicates.add("$relativeColName LIKE ? ESCAPE '\\'")
            args.add(escapeLike(expectedRelative) + "%")
        }
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1" +
            if (filtered) " AND (${predicates.joinToString(" OR ")})" else ""

        try {
        resolver.query(collection, projection.toTypedArray(), selection, if (filtered) args.toTypedArray() else null, sortOrder)?.use { cursor ->
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

            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getLong(idCol)
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                val displayName = if (nameCol >= 0) cursor.getString(nameCol) else null
                val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                val ext = (displayName ?: data ?: "").substringAfterLast('.', "").lowercase()
                if (ext !in audioExtensions) continue

                // OR, not exclusive: DATA can point at a different FUSE path
                // (/sdcard vs /storage/emulated/0) while RELATIVE_PATH still
                // matches — an AND here emptied whole folders.
                if (!matchesFolder(folderPath, data, relPath)) continue

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
                                "content://media/external/audio/albumart".toUri(),
                                it,
                            ).toString()
                        },
                        path = data,
                    )
                )
            }
        }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Solo en el fallo del query (OEM con LIKE roto) se
            // reintenta sin filtro: una carpeta VACÍA no debe
            // desencadenar el escaneo de todo el dispositivo
            // (el reintento anterior devolvía toda la biblioteca
            // como "contenido de la carpeta").
            if (!filtered) throw e
            return fromMediaStore(folderPath, filtered = false)
        }
        return results
    }

    suspend fun fromSaf(treeUri: String): List<SongMedia> {
        val resolver = resolver ?: return emptyList()
        val uri = treeUri.toUri()
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
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.add(rootDocId)

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            if (!visited.add(dir)) continue
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, dir)
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
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            queue.addAll(subDirs)
        }
        return result
    }
}

// El prefijo "/sdcard" es intencional (alias symlink que algunas ROM
// devuelven en DATA). Ver nota en el cuerpo.
@Suppress("SdCardPath")
internal fun relativeFolderPath(path: String): String? {
    val normalized = path.trimEnd('/') + "/"
    // "/sdcard" se mantiene a propósito: es un alias symlink persistente
    // que MediaStore devuelve en algunas ROM, y no se puede resolver sin
    // montar nada. Las rutas literales de arriba solo se comparan como
    // prefijo, nunca se abren.
    val root = listOf("/storage/emulated/0/", "/sdcard/", "/storage/self/primary/")
        .firstOrNull { normalized.startsWith(it) } ?: return null
    return normalized.removePrefix(root)
}

internal fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

internal fun matchesFolder(folder: String, data: String?, relative: String?): Boolean {
    val prefix = folder.trimEnd('/') + "/"
    val expected = relativeFolderPath(folder)
    return data?.startsWith(prefix) == true ||
        (expected != null && relative != null &&
            (expected.isEmpty() || (relative.trimEnd('/') + "/").startsWith(expected)))
}
