package com.xly.u_8th.auto

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.Executors

data class Track(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val trackNumber: Int,
    val durationMs: Long
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String?,
    val trackCount: Int,
    val artUri: String?
)

object LibraryProvider {

    // Fuente única de extensiones de audio: antes estaba copiado en
    // MainActivity (x2) y aquí; añadir un formato tocaba 3 sitios.
    val audioExtensions = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "wma", "opus")

    @Volatile
    private var cachedTracks: List<Track> = emptyList()

    @Volatile
    private var cachedAlbums: List<Album> = emptyList()

    /** true tras el primer scan completado: distingue "vacío por falta de
     *  permiso" de "aún no se ha escaneado" (Auto muestra loading solo en
     *  el segundo caso, si no entraría en un loop de loading eterno). */
    @Volatile
    var refreshedOnce = false
        private set

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "xly-library") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshLock = Any()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    private var refreshing = false

    fun tracks(): List<Track> = cachedTracks

    fun albums(): List<Album> = cachedAlbums

    fun tracksForAlbum(albumId: Long): List<Track> =
        cachedTracks.filter { it.albumId == albumId }.sortedBy { it.trackNumber }

    fun trackByIndex(index: Int): Track? =
        cachedTracks.getOrNull(index)

    /**
     * Escaneo de MediaStore en background con single-flight: llamadas
     * concurrentes comparten el mismo scan y todos los callbacks se
     * invocan (en el main looper) al terminar. Antes onGetTemplate del
     * coche escaneaba sincrónicamente en el main thread = ANR al conectar
     * con bibliotecas grandes.
     */
    fun refreshAsync(context: Context, onDone: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        if (onDone != null) {
            synchronized(refreshLock) { pendingCallbacks.add(onDone) }
        }
        if (refreshing) return
        refreshing = true
        executor.execute {
            try {
                refresh(appContext)
            } finally {
                refreshing = false
                val cbs = synchronized(refreshLock) {
                    val copy = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    copy
                }
                mainHandler.post {
                    for (cb in cbs) runCatching { cb() }
                }
            }
        }
    }

    /**
     * Scan sincrónico. @Synchronized: cachedTracks/cachedAlbums se escriben
     * en dos pasos y sin exclusión dos scans concurrentes podían dejar la
     * caché mixta (pistas del scan A + álbumes del scan B).
     */
    @Synchronized
    fun refresh(context: Context) {
        try {
            val tracks = readTracks(context)
            cachedTracks = tracks
            cachedAlbums = buildAlbums(tracks)
        } catch (_: SecurityException) {
            // Sin permiso READ_MEDIA_AUDIO: deja caché vacía en vez de
            // crashear el CarAppService. La UI muestra mensaje.
            cachedTracks = emptyList()
            cachedAlbums = emptyList()
        } catch (_: Exception) {
            // Otros fallos (cursor nulo, etc.): conserva la caché previa.
        } finally {
            refreshedOnce = true
        }
    }

    private fun buildAlbums(tracks: List<Track>): List<Album> {
        // albumId != 0 es clave suficiente por sí solo: la clave antigua
        // (albumId, título.minúsculas) partía en dos el mismo álbum cuando
        // los tags traían distinta capitalización. Solo los "Desconocido"
        // (albumId = 0) se separan por título para no fusionar temas no
        // relacionados entre sí.
        return tracks
            .groupBy {
                if (it.albumId != 0L) "id:${it.albumId}"
                else "unk:${(it.album ?: "Desconocido").lowercase()}"
            }
            .map { (_, list) ->
                val first = list.first()
                Album(
                    id = first.albumId,
                    title = first.album ?: "Desconocido",
                    artist = first.artist,
                    trackCount = list.size,
                    artUri = albumArtUri(first.albumId).toString()
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    private fun albumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId)

    private fun readTracks(context: Context): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val sortOrder = "${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC"

        val results = mutableListOf<Track>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                val ext = (name ?: "").substringAfterLast('.', "").lowercase()
                if (ext !in audioExtensions) continue

                results.add(
                    Track(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        title = if (titleCol >= 0) (cursor.getString(titleCol) ?: name ?: "Sin título")
                                else (name ?: "Sin título"),
                        artist = if (artistCol >= 0) cursor.getString(artistCol) else null,
                        album = if (albumCol >= 0) cursor.getString(albumCol) else null,
                        albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L,
                        trackNumber = if (trackCol >= 0) cursor.getInt(trackCol) else 0,
                        durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    )
                )
            }
        }
        return results
    }
}
