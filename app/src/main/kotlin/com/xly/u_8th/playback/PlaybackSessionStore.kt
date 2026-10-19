package com.xly.u_8th.playback

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class PlaybackSessionStore(context: Context) {
    private val context = context.applicationContext
    private val file by lazy { AtomicFile(File(this.context.filesDir, "playback-session.bin")) }
    private val mutex = Mutex()

    private companion object {
        /** Sondeos de legibilidad simultáneos al restaurar la cola. */
        const val READ_PROBE_BATCH = 8
    }

    suspend fun load(): PlaybackSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                file.openRead().use { PlaybackSessionCodec.read(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Descarta las canciones que ya no se pueden abrir (fichero movido,
     * permiso revocado) y reapunta el índice a la siguiente entrada viva.
     *
     * El sondeo es una ida y vuelta al binder por canción: en cola de 5 000
     * pistas, hacerlos en serie alargaba de forma visible el arranque en frío
     * antes de poder reproducir nada.
     */
    suspend fun readableSession(session: PlaybackSession): PlaybackSession = withContext(Dispatchers.IO) {
    val readable = BooleanArray(session.songs.size)
    coroutineScope {
        // Por lotes: todos en paralelo a la vez abrirían un descriptor por
        // canción y agotarían los límites del binder.
        session.songs.indices.chunked(READ_PROBE_BATCH).forEach { batch ->
            currentCoroutineContext().ensureActive()
            batch.map { index ->
                async { readable[index] = canRead(session.songs[index]) }
            }.awaitAll()
        }
    }
    session.retainingIndexed { readable[it] }
}

    /** ¿Se puede abrir [song]? Una llamada al binder, con `content://` o `file://`. */
    private suspend fun canRead(song: Song): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(song.playbackUriString.toUri(), "r")
            ?.use { true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    suspend fun save(session: PlaybackSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stream = file.startWrite()
            try {
                PlaybackSessionCodec.write(session, stream)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }
    }
}


/**
 * Serializa las escrituras de sesión: cada `schedule` cancela la anterior y
 * espera [DEBOUNCE_MS] antes de escribir, de modo que `snapshot()` se calcula
 * solo cuando de verdad toca guardar.
 *
 * @param snapshot perezoso a propósito: `Player.Listener.onEvents`
 *  dispara varias veces por segundo y tomar la instantánea de una cola de
 *  5 000 temas en cada evento es trabajo tirado (copia dos listas largas).
 */
class SessionSaveScheduler(
    private val scope: CoroutineScope,
    /**
     * Contexto donde se evalúa `snapshot`: lee el ExoPlayer, que solo admite
     * acceso desde su hilo (el principal); fuera de él lanza y la sesión no
     * se guardaba nunca salvo en onDestroy.
     */
    private val snapshotContext: CoroutineContext = EmptyCoroutineContext,
    private val save: suspend (PlaybackSession) -> Unit,
) {
    private var pending: Job? = null

    @Volatile
    var latest: PlaybackSession? = null
        private set

    // `snapshot` va el último a propósito: así `schedule { … }` usa la
    // sintaxis de lambda final.
    @Synchronized
    fun schedule(immediate: Boolean = false, snapshot: () -> PlaybackSession) {
        pending?.cancel()
        pending = scope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            val session = try {
                withContext(snapshotContext) { snapshot() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            latest = session
            try {
                save(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 750L
    }
}

class RestoreGate {
    var revision = 0L
        private set

    fun command() {
        revision++
    }

    fun accepts(startRevision: Long): Boolean = revision == startRevision
}
