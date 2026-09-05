package com.xly.u_8th

import android.content.ContentUris
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ryanheise.audioservice.AudioServiceFragmentActivity
import com.xly.u_8th.auto.AutoBridge
import com.xly.u_8th.auto.AutoPlaybackSnapshot
import com.xly.u_8th.auto.AutoPlaybackState
import com.xly.u_8th.auto.AutoQueueItem
import com.xly.u_8th.auto.LibraryProvider
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AudioServiceFragmentActivity() {
    private val CHANNEL = "com.xly.u_8th/files"
    private val AUTO_EVENTS = "com.xly.u_8th/auto_events"

    // Un solo hilo para todo el canal: antes cada invocación lanzaba un
    // Thread{} suelto (scans concurrentes sin deduplicar y resultados que
    // podían llegar desordenados: una respuesta vieja pisando a una nueva).
    private val channelExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xly-files-channel")
    }
    // Generación de peticiones de listado: si llega una nueva mientras una
    // vieja sigue encolada, la vieja se descarta en vez de responder tarde.
    private val listGen = AtomicLong(0)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "listAudioFiles" -> {
                    val treeUri = call.argument<String>("treeUri")
                    if (treeUri != null) {
                        // SAF/MediaStore hace queries a ContentResolver que con
                        // árboles grandes bloquean el hilo UI (ANR). Va a fondo.
                        val gen = listGen.incrementAndGet()
                        channelExecutor.execute {
                            try {
                                val folderPath = resolveFolderPath(treeUri)
                                val files = if (folderPath != null) {
                                    listFromMediaStore(folderPath)
                                } else {
                                    listFromSAF(treeUri)
                                }
                                if (gen == listGen.get()) runOnUiThread { result.success(files) }
                            } catch (e: Exception) {
                                if (gen == listGen.get()) runOnUiThread { result.error("LIST_ERROR", e.message, null) }
                            }
                        }
                    } else {
                        result.error("NO_URI", "No tree URI provided", null)
                    }
                }
                "extractArt" -> {
                    val path = call.argument<String>("path")
                    val uri = call.argument<String>("uri")
                    val artUri = call.argument<String>("artUri")
                    channelExecutor.execute {
                        try {
                            val bytes = extractArtBytes(path, uri, artUri)
                            runOnUiThread { result.success(bytes) }
                        } catch (t: Throwable) {
                            // Sin esto un OOM/excepción dejaba el invokeMethod
                            // de Dart colgado para siempre.
                            runOnUiThread { result.error("ART_ERROR", t.message, null) }
                        }
                    }
                }
                "pushPlaybackSnapshot" -> {
                    val arg = call.arguments as? Map<*, *> ?: emptyMap<String, Any?>()
                    // Snapshot ligero: Dart solo envía 'queue' cuando cambia
                    // (los ticks de posición la omiten). Conservar la anterior
                    // evita TransactionTooLarge con 500 temas cada 2s.
                    val prev = AutoPlaybackState.current()
                    val queue: List<AutoQueueItem> = if (arg.containsKey("queue")) {
                        val queueRaw = arg["queue"] as? List<*> ?: emptyList<Any?>()
                        queueRaw.mapNotNull { q ->
                            val qm = q as? Map<*, *> ?: return@mapNotNull null
                            AutoQueueItem(
                                uri = (qm["uri"] as? String) ?: "",
                                title = (qm["title"] as? String) ?: "",
                                artist = (qm["artist"] as? String),
                                album = (qm["album"] as? String),
                                durationMs = ((qm["durationMs"] as? Number)?.toLong()) ?: 0L
                            )
                        }
                    } else {
                        prev.queue
                    }
                    val snapshot = AutoPlaybackSnapshot(
                        uri = (arg["uri"] as? String) ?: "",
                        title = (arg["title"] as? String) ?: "",
                        artist = (arg["artist"] as? String) ?: "",
                        album = (arg["album"] as? String) ?: "",
                        positionMs = ((arg["positionMs"] as? Number)?.toLong()) ?: 0L,
                        durationMs = ((arg["durationMs"] as? Number)?.toLong()) ?: 0L,
                        playing = (arg["playing"] as? Boolean) ?: false,
                        shuffled = (arg["shuffled"] as? Boolean) ?: false,
                        loopMode = ((arg["loopMode"] as? Number)?.toInt()) ?: 0,
                        queueIndex = ((arg["queueIndex"] as? Number)?.toInt()) ?: prev.queueIndex,
                        queue = queue
                    )
                    AutoPlaybackState.update(snapshot)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, AUTO_EVENTS).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    // AutoBridge.deliver ya publica en el main looper con su
                    // propio Handler: runOnUiThread aquí capturaba la Activity
                    // en el singleton AutoBridge (fuga hasta cancelar el canal).
                    AutoBridge.attach { payload -> events?.success(payload) }
                }

                override fun onCancel(arguments: Any?) {
                    AutoBridge.detach()
                }
            }
        )
    }

    private fun resolveFolderPath(treeUri: String): String? {
        return try {
            val uri = Uri.parse(treeUri)
            if (uri.scheme == "content") {
                val id = try {
                    DocumentsContract.getTreeDocumentId(uri)
                } catch (_: Exception) { null }
                if (id != null && id.startsWith("primary:")) {
                    "/storage/emulated/0/" + id.removePrefix("primary:")
                } else null
            } else {
                treeUri
            }
        } catch (_: Exception) { null }
    }

    private fun listFromMediaStore(folderPath: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // DATA está deprecada desde Android 10 y en 11+ suele venir null por
        // scoped storage: se pide RELATIVE_PATH cuando existe y se filtra en
        // código en vez de confiar en "DATA LIKE path%" (antes devolvía vacío
        // y caía al file-picker en cada arranque).
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        var relativeColName: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            relativeColName = MediaStore.Audio.Media.RELATIVE_PATH
            projection.add(relativeColName)
        }

        val normalizedPath = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
        // Prefijo relativo esperado: "/storage/emulated/0/Music/Sub" -> "Music/Sub/"
        val expectedRelative = normalizedPath
            .removePrefix("/storage/emulated/0/")
            .trim('/')
            .let { if (it.isEmpty()) "" else "$it/" }
        val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

        // IS_MUSIC=1: sin esto la query traía tonos de llamada, grabaciones y
        // notificaciones con extensión de audio (LibraryProvider ya lo hacía).
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        // Tope anti-TransactionTooLarge, mismo cap que la ruta SAF.
        val cap = 2000

        contentResolver.query(collection, projection.toTypedArray(), selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val relCol = if (relativeColName != null) cursor.getColumnIndex(relativeColName) else -1

            while (cursor.moveToNext() && results.size < cap) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id).toString()
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                val displayName = if (nameCol >= 0) cursor.getString(nameCol) else null
                val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                val ext = (displayName ?: data ?: "").substringAfterLast('.', "").lowercase()
                if (ext !in LibraryProvider.audioExtensions) continue

                // OR, no excluyente: si DATA apunta a una ruta FUSE distinta
                // (/sdcard vs /storage/emulated/0) RELATIVE_PATH sí matchea y
                // antes la carpeta aparecía vacía.
                val dataMatch = !data.isNullOrEmpty() &&
                    (data.startsWith(normalizedPath) || data == folderPath)
                val relMatch = !relPath.isNullOrEmpty() && expectedRelative.isNotEmpty() &&
                    (relPath.startsWith(expectedRelative) || relPath.trim('/') == expectedRelative.trim('/'))
                val inFolder = dataMatch || relMatch || expectedRelative.isEmpty()
                if (!inFolder) continue

                val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else null

                results.add(mapOf(
                    "uri" to contentUri,
                    "name" to (displayName ?: ""),
                    "title" to (if (titleCol >= 0) cursor.getString(titleCol) else null),
                    "artist" to (if (artistCol >= 0) cursor.getString(artistCol) else null),
                    "album" to (if (albumCol >= 0) cursor.getString(albumCol) else null),
                    "track" to (if (trackCol >= 0) cursor.getInt(trackCol) else 0),
                    "duration" to (if (durationCol >= 0) cursor.getLong(durationCol) else 0L),
                    // URI de portada derivable del albumId de la propia fila:
                    // la query completa de la tabla Albums que había antes
                    // duplicaba el coste de cada listado.
                    "artUri" to albumId?.let {
                        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it).toString()
                    },
                    "path" to data
                ))
            }
        }

        // Si el filtro no matcheó nada (p.ej. carpeta fuera de MediaStore),
        // devuelve vacío para que Dart haga scan directo/SAF en vez de
        // mostrar biblioteca vacía.
        return results
    }

    private fun listFromSAF(treeUri: String): List<Map<String, Any?>> {
        val uri = Uri.parse(treeUri)
        try {
            // El presupuesto de permisos persistibles es limitado: solo se
            // toma si aún no está (antes se pedía en cada listado).
            val alreadyPersisted = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!alreadyPersisted) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {}

        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val result = mutableListOf<Map<String, Any?>>()
        val cap = 2000
        // BFS con UN cursor por directorio: DocumentFile.listFiles() hace una
        // query ContentProvider POR HIJO (N+1) y tardaba segundos en árboles
        // grandes. Mismo cap de profundidad (12) que antes.
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
                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
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
                            if (ext in LibraryProvider.audioExtensions) {
                                result.add(mapOf(
                                    "uri" to DocumentsContract.buildDocumentUriUsingTree(uri, docId).toString(),
                                    "name" to name,
                                    "title" to null,
                                    "artist" to null,
                                    "album" to null,
                                    "track" to 0,
                                    "duration" to 0L,
                                    "artUri" to null,
                                    "path" to null
                                ))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Directorio sin permiso: se omiten sus hijos, el scan sigue.
            }
            for (id in subDirs) queue.add(Dir(id, dir.depth + 1))
        }
        return result
    }

    private fun extractArtBytes(path: String?, uri: String?, artUri: String?): ByteArray? {
        val retriever = MediaMetadataRetriever()
        try {
            // content:// primero: el file path suele ser ilegible por scoped
            // storage y setDataSource(path) fallaba aunque el uri funcionase.
            when {
                !uri.isNullOrBlank() -> retriever.setDataSource(this, Uri.parse(uri))
                !path.isNullOrBlank() -> retriever.setDataSource(path)
                else -> return readArtStream(artUri)
            }
            val embedded = retriever.embeddedPicture
            // Esta rama devolvía la imagen SIN topear: una carátula 4K
            // embebida (3-10 MB en FLAC) viajaba entera por el MethodChannel
            // (TransactionTooLarge/OOM). Mismo pipeline que readArtStream.
            if (embedded != null && embedded.isNotEmpty()) return compressArtIfNeeded(embedded)
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return readArtStream(artUri)
    }

    /**
     * Downsample a ~512px + JPEG 85 si supera ~300KB. Si no se puede
     * re-comprimir devuelve null (la UI ya maneja portada ausente): cortar
     * los bytes a ciegas producía JPEG corruptos que no decodificaban.
     */
    private fun compressArtIfNeeded(raw: ByteArray): ByteArray? {
        if (raw.size <= 300 * 1024) return raw
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 &&
                bounds.outHeight / (sample * 2) >= 512) {
                sample *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) null else bytes
        } catch (_: Exception) {
            null
        }
    }

    private fun readArtStream(artUri: String?): ByteArray? {
        if (artUri.isNullOrBlank()) return null
        return try {
            val raw = contentResolver.openInputStream(Uri.parse(artUri))?.use { it.readBytes() }
                ?: return null
            compressArtIfNeeded(raw)
        } catch (_: Exception) {
            null
        }
    }
}
