package com.xly.u_8th.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor

/**
 * Singleton wrapper over the media3 [MediaController] connected to
 * [PlaybackService]. All UI/car commands go through here; the methods are
 * suspend so callers never block the main thread on the session binder.
 */
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {

    private val mutex = Mutex()

    @Volatile
    private var controller: MediaController? = null

    /** Builds the controller once and caches it (rebuilds after a disconnect). */
    suspend fun awaitController(): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        return mutex.withLock {
            controller?.takeIf { it.isConnected }?.let { return it }
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
        awaitController().play()
    }

    suspend fun pause() {
        awaitController().pause()
    }

    suspend fun togglePlay() {
        val controller = awaitController()
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    suspend fun next() {
        awaitController().seekToNextMediaItem()
    }

    suspend fun prev() {
        awaitController().seekToPreviousMediaItem()
    }

    suspend fun seekTo(ms: Long) {
        awaitController().seekTo(ms)
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
        val controller = awaitController()
        if (songs.size <= MAX_PARCELABLE_SONGS) {
            // Payload small enough for the binder transaction: full songs travel.
            val args = Bundle().apply {
                putParcelableArrayList(PlaybackService.ARG_SONGS, ArrayList(songs))
                putCommonArgs(safeIndex, label, autoPlay, shuffled, resumeIfSameSong)
            }
            sendOn(controller, PlaybackService.ACTION_PLAY_SONGS, args)
        } else {
            // Binder safety (~1MB TransactionTooLarge limit): URIs only; the
            // service rebuilds the songs from the PlaybackCatalog.
            val args = Bundle().apply {
                putStringArrayList(PlaybackService.ARG_URIS, ArrayList(songs.map { it.playUri }))
                putCommonArgs(safeIndex, label, autoPlay, shuffled, resumeIfSameSong)
            }
            sendOn(controller, PlaybackService.ACTION_PLAY_URIS, args)
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
        sendCustom(PlaybackService.ACTION_ADD_NEXT) { putParcelableCompat(PlaybackService.ARG_SONG, song) }
    }

    suspend fun addEnd(song: Song) {
        PlaybackCatalog.put(song)
        sendCustom(PlaybackService.ACTION_ADD_END) { putParcelableCompat(PlaybackService.ARG_SONG, song) }
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

    private suspend fun sendOn(controller: MediaController, action: String, args: Bundle) {
        try {
            controller.sendCustomCommand(SessionCommand(action, args), args)
        } catch (_: Exception) {
        }
    }

    private suspend fun sendCustom(action: String, build: Bundle.() -> Unit) {
        val args = Bundle().apply(build)
        sendOn(awaitController(), action, args)
    }

    @Suppress("DEPRECATION")
    private fun Bundle.putParcelableCompat(key: String, song: Song) {
        putParcelable(key, song)
    }    companion object {
        /** Above this, payload switches to URIs (binder transaction limit). */
        private const val MAX_PARCELABLE_SONGS = 400

        @Volatile
        private var instance: PlayerConnection? = null

        /** App-scoped singleton. */
        fun get(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ): PlayerConnection = instance ?: synchronized(this) {
            instance ?: PlayerConnection(context.applicationContext, scope)
                .also { instance = it }
        }
    }
}
