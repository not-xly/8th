package com.xly.u_8th.auto

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.xly.u_8th.EighthApplication
import kotlinx.coroutines.runBlocking
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

/**
 * [key] identifica el álbum de forma inequívoca: "id:<albumId>" para los
 * que MediaStore conoce y "unk:<título normalizado>" para los que no
 * (albumId = 0). Usar solo [id] mezclaba todos los álbumes "Desconocido"
 * en uno: `tracksForAlbum(0)` devolvía la unión de todos ellos.
 */
data class Album(
    val key: String,
    val id: Long,
    val title: String,
    val artist: String?,
    val trackCount: Int,
    val artUri: String?
)

object LibraryProvider {

    private const val ID_PREFIX = "id:"
    private const val UNKNOWN_PREFIX = "unk:"

    /**
     * Pistas + álbumes se publican como un único objeto inmutable con UNA
     * escritura volatile. Con dos campos separados un lector podía observar
     * pistas del scan A con álbumes del scan B (los recuentos no cuadraban).
     */
    private data class Snapshot(
        val tracks: List<Track>,
        val albums: List<Album>,
        val ok: Boolean,
    )

    @Volatile
    private var snapshot = Snapshot(emptyList(), emptyList(), ok = false)

    /**
     * true tras el primer scan **concediendo o denegando** el permiso: el
     * host necesita distinguir "vacío por falta de permiso" de "aún no se ha
     * escaneado" (muestra loading solo en el segundo caso, si no entraría en
     * un loop de loading eterno).
     */
    val refreshedOnce: Boolean get() = snapshot.ok

    /** Ultimo escaneo fallido: permite reintentar tras conceder permiso. */
    @Volatile
    private var lastScanFailed = false

    @Volatile
    private var lastScanAt = 0L

    /**
     * El escaneo previo falló (p. ej. sin `READ_MEDIA_AUDIO`) o nunca se
     * hizo. `MainScreen` pide el permiso en el móvil y el coche corre en el
     * mismo proceso: sin este aviso la biblioteca del coche se quedaba
     * vacía hasta reiniciar la app.
     */
    fun needsRescan(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Boolean =
        !snapshot.ok || (lastScanFailed && nowMs - lastScanAt > RESCAN_COOLDOWN_MS)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "xly-library") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshLock = Any()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    private var refreshing = false

    fun tracks(): List<Track> = snapshot.tracks

    fun albums(): List<Album> = snapshot.albums

    /** Pistas del álbum [key] ("id:123" / "unk:desconocido"), por nº de pista. */
    fun tracksForAlbum(key: String): List<Track> =
        snapshot.tracks
            .filter {
                if (key.startsWith(ID_PREFIX)) {
                    it.albumId == key.removePrefix(ID_PREFIX).toLongOrNull()
                } else {
                    it.albumId == 0L && unknownAlbumKey(it.album) == key
                }
            }
            .sortedBy { it.trackNumber }

    private fun unknownAlbumKey(album: String?): String =
        UNKNOWN_PREFIX + (album ?: "Desconocido").lowercase()

    /**
     * Escaneo de MediaStore en background con single-flight: llamadas
     * concurrentes comparten el mismo scan y todos los callbacks se
     * invocan (en el main looper) al terminar. Antes onGetTemplate del
     * coche escaneaba sincrónicamente en el main thread = ANR al conectar
     * con bibliotecas grandes.
     */
    fun refreshAsync(context: Context, onDone: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        // Comprobación y toma del latch ATÓMICAS: dos llamadas
        // concurrentes podían pasar las dos el `if (refreshing)`
        // (check-then-act sobre el hilo del coche y el executor)
        // y lanzar dos escaneos.
        val startScan = synchronized(refreshLock) {
            if (onDone != null) pendingCallbacks.add(onDone)
            if (refreshing) false else {
                refreshing = true
                true
            }
        }
        if (!startScan) return
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
     * Scan sincrónico. Publica pistas y álbumes con una sola escritura, de
     * modo que ningún lector pueda ver una mezcla de dos escaneos. Solo lo
     * llama [refreshAsync] (hilo único): no necesita sincronización propia.
     */
    fun refresh(context: Context) {
        lastScanAt = android.os.SystemClock.elapsedRealtime()
        try {
            val tracks = readTracks(context)
            snapshot = Snapshot(tracks, buildAlbums(tracks), ok = true)
            lastScanFailed = false
        } catch (_: SecurityException) {
            // Sin permiso READ_MEDIA_AUDIO: deja caché vacía en vez de
            // crashear el CarAppService. La UI muestra mensaje y
            // needsRescan() permite reintentar cuando se conceda.
            snapshot = Snapshot(emptyList(), emptyList(), ok = true)
            lastScanFailed = true
        } catch (_: Exception) {
            // Otros fallos (cursor nulo, columna ausente…): conserva la
            // caché previa. `ok` pasa a true igualmente: un escaneo SÍ se
            // intentó, y dejarlo en false metía al host en un spinner
            // perpetuo re-lanzando el escaneo en cada onGetTemplate.
            if (!snapshot.ok) snapshot = snapshot.copy(ok = true)
            lastScanFailed = true
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
                if (it.albumId != 0L) ID_PREFIX + it.albumId else unknownAlbumKey(it.album)
            }
            .map { (key, list) ->
                val first = list.first()
                Album(
                    key = key,
                    id = first.albumId,
                    title = first.album ?: "Desconocido",
                    artist = first.artist,
                    trackCount = list.size,
                    // albumId = 0 no tiene carátula derivable: un
                    // "albumart/0" nunca resuelve y el host gastaba una
                    // descarga fallida por cada álbum desconocido.
                    artUri = if (first.albumId != 0L) albumArtUri(first.albumId).toString() else null,
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    private fun albumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId)

    /**
     * Misma fuente que la biblioteca del móvil (carpeta o archivos guardados
     * en Prefs, vía [com.xly.u_8th.data.LibraryRepository.restoreSongs]).
     * Antes se consultaba TODO el audio de MediaStore, así que el coche
     * mostraba canciones que el móvil filtraba (otras carpetas, notas de voz).
     * Corre en el executor propio: bloquear aquí no toca el main thread.
     */
    private fun readTracks(context: Context): List<Track> {
        val songs = runBlocking {
            EighthApplication.get(context).libraryRepository.restoreSongs()
        }.orEmpty()
        if (songs.isEmpty() && !hasAudioPermission(context)) {
            // Conserva la semántica previa: sin permiso → reintento cuando
            // se conceda (needsRescan).
            throw SecurityException("READ_MEDIA_AUDIO no concedido")
        }
        return songs
            .map { s ->
                val uri = s.playUri
                Track(
                    id = idFromUri(uri) ?: uri.hashCode().toLong(),
                    uri = uri,
                    title = s.displayTitle,
                    artist = s.artist,
                    album = s.album,
                    albumId = s.albumArtUri?.let { idFromUri(it) } ?: 0L,
                    trackNumber = s.trackNumber ?: 0,
                    durationMs = s.durationMs ?: 0L,
                )
            }
            .sortedWith(compareBy({ it.album?.lowercase() ?: "" }, { it.trackNumber }))
    }

    /** Último segmento numérico de un content:// de MediaStore, si lo hay. */
    private fun idFromUri(uri: String): Long? =
        if (uri.startsWith("content://media/")) uri.substringAfterLast('/').toLongOrNull() else null

    private fun hasAudioPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Margen para reintentar tras conceder el permiso, sin spin de escaneo. */
    private const val RESCAN_COOLDOWN_MS = 5_000L
}
