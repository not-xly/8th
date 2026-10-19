package com.xly.u_8th.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor

/**
 * Singleton wrapper over the media3 [MediaController] connected to
 * [PlaybackService]. All UI/car commands go through here; the methods are
 * suspend so callers never block the main thread on the session binder.
 */
class PlayerConnection private constructor(private val context: Context, private val scope: CoroutineScope) {

    private val mutex = Mutex()

    @Volatile
    private var controller: MediaController? = null

    /**
     * Builds the controller once and caches it (rebuilds after a disconnect).
     * MediaController requires every call on its application looper, so all
     * controller work is confined to the main dispatcher.
     */
    suspend fun awaitController(): MediaController = withContext(Dispatchers.Main.immediate) {
        controller?.takeIf { it.isConnected }?.let { return@withContext it }
        mutex.withLock {
            controller?.takeIf { it.isConnected }?.let { return@withLock it }
            controller?.release()
            buildController().also { controller = it }
        }
    }

    private suspend fun buildController(): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val result = CompletableDeferred<MediaController>()
        future.addListener(
            {
                try {
                    result.complete(future.get())
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                }
            },
            Executor { it.run() },
        )
        return try {
            result.await()
        } catch (t: Throwable) {
            runCatching { MediaController.releaseFuture(future) }
            throw t
        }
    }

    /** Fire-and-forget helper: safe to call from non-suspend UI/car contexts. */
    fun launch(block: suspend PlayerConnection.() -> Unit) {
        scope.launch {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // A failed session op must never crash the caller (car taps,
                // UI buttons) — the state stays whatever the service had.
            }
        }
    }

    // ---- Transport ----

    suspend fun play() {
        withContext(Dispatchers.Main.immediate) { awaitController().play() }
    }

    suspend fun pause() {
        withContext(Dispatchers.Main.immediate) { awaitController().pause() }
    }

    suspend fun togglePlay() = withContext(Dispatchers.Main.immediate) {
        val controller = awaitController()
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
        Unit
    }

    suspend fun next() {
        withContext(Dispatchers.Main.immediate) { awaitController().seekToNextMediaItem() }
    }

    suspend fun prev() {
        withContext(Dispatchers.Main.immediate) { awaitController().seekToPreviousMediaItem() }
    }

    suspend fun seekTo(ms: Long) {
        withContext(Dispatchers.Main.immediate) { awaitController().seekTo(ms) }
    }

    /** Custom SEEK_INDEX (not seekToDefaultPosition) so QueueManager syncs the index. */
    suspend fun seekToIndex(index: Int) =
        sendCustom(PlaybackService.ACTION_SEEK_INDEX) { putInt(PlaybackService.ARG_INDEX, index) }

    // ---- Queue ----

    suspend fun playQueue(
        songs: List<Song>,
        initialIndex: Int = 0,
        label: String,
        autoPlay: Boolean = true,
        shuffled: Boolean = false,
        resumeIfSameSong: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        PlaybackCatalog.putAll(songs)
        val safeIndex = initialIndex.coerceIn(0, songs.size - 1)
        // Solo un token: la cola se queda en el PlaybackCatalog (mismo
        // proceso). Enviar los Song o las URIs por el binder podía superar
        // su límite (~1 MB) con colas grandes y el álbum no sonaba.
        val token = PlaybackCatalog.stageQueue(songs)
        val args = Bundle().apply {
            putString(PlaybackService.ARG_QUEUE_TOKEN, token)
            putCommonArgs(safeIndex, label, autoPlay, shuffled, resumeIfSameSong)
        }
        val ok = try {
            awaitResult(awaitController(), PlaybackService.ACTION_PLAY_URIS, args)
        } catch (ce: CancellationException) {
            PlaybackCatalog.takeStaged(token)
            throw ce
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            // Antes se tragaba: ahora la UI avisa de que no sonó nada.
            PlaybackCatalog.takeStaged(token)
            PlayerBus.completeResult(songs.size)
        }
    }

    private fun Bundle.putCommonArgs(
        index: Int,
        label: String,
        autoPlay: Boolean,
        shuffled: Boolean,
        resumeIfSameSong: Boolean,
    ) {
        putInt(PlaybackService.ARG_INDEX, index)
        putString(PlaybackService.ARG_LABEL, label)
        putBoolean(PlaybackService.ARG_AUTO_PLAY, autoPlay)
        putBoolean(PlaybackService.ARG_SHUFFLED, shuffled)
        putBoolean(PlaybackService.ARG_RESUME, resumeIfSameSong)
    }

    suspend fun addNext(song: Song) {
        PlaybackCatalog.put(song)
        sendCustom(PlaybackService.ACTION_ADD_NEXT) { putString(PlaybackService.ARG_URI, song.playUri) }
    }

    suspend fun addEnd(song: Song) {
        PlaybackCatalog.put(song)
        sendCustom(PlaybackService.ACTION_ADD_END) { putString(PlaybackService.ARG_URI, song.playUri) }
    }

    suspend fun removeAt(index: Int) =
        sendCustom(PlaybackService.ACTION_REMOVE_AT) { putInt(PlaybackService.ARG_INDEX, index) }

    suspend fun move(from: Int, to: Int) =
        sendCustom(PlaybackService.ACTION_MOVE) {
            putInt(PlaybackService.ARG_FROM, from)
            putInt(PlaybackService.ARG_TO, to)
        }

    // ---- Modes ----

    suspend fun setShuffled(enable: Boolean) =
        sendCustom(PlaybackService.ACTION_SET_SHUFFLE) {
            putBoolean(PlaybackService.ARG_ENABLE, enable)
        }

    suspend fun cycleLoop() = sendCustom(PlaybackService.ACTION_CYCLE_LOOP) {}

    suspend fun refreshArt() = sendCustom(PlaybackService.ACTION_REFRESH_ART) {}

    /** "Aleatorio Todo" over the whole library; handled service-side. */
    suspend fun shuffleAll() = sendCustom(PlaybackService.ACTION_SHUFFLE_ALL) {}

    // ---- Internals ----

    private suspend fun sendOn(controller: MediaController, action: String, args: Bundle) = withContext(Dispatchers.Main.immediate) {
        try {
            controller.sendCustomCommand(SessionCommand(action, args), args)
        } catch (_: Exception) {
            // The state stays whatever the service had; never crash callers.
        }
        Unit
    }

    /** Envía [action] y espera la respuesta del servicio; true si tuvo éxito. */
    private suspend fun awaitResult(controller: MediaController, action: String, args: Bundle): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val future = controller.sendCustomCommand(SessionCommand(action, args), args)
            val result = CompletableDeferred<Boolean>()
            future.addListener(
                {
                    result.complete(
                        runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
                    )
                },
                Executor { it.run() },
            )
            result.await()
        }

    private suspend fun sendCustom(action: String, build: Bundle.() -> Unit) {
        val args = Bundle().apply(build)
        sendOn(awaitController(), action, args)
    }

    companion object {
        // Holds the application context only — never an Activity — so the
        // static reference cannot leak a UI component.
        @Suppress("StaticFieldLeak")
        @Volatile
        private var instance: PlayerConnection? = null

        /**
         * App-scoped singleton. [scope] is nullable on purpose: a default
         * `CoroutineScope(...)` parameter allocated a throwaway scope on
         * every call that did not pass one.
         */
        fun get(
            context: Context,
            scope: CoroutineScope? = null,
        ): PlayerConnection = instance ?: synchronized(this) {
            instance ?: PlayerConnection(
                context.applicationContext,
                scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ).also { instance = it }
        }
    }
}
