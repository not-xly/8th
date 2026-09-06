package com.xly.u_8th.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.xly.u_8th.data.ArtCache
import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Minimal service dependency seam. MediaSessionService is instantiated by the
 * system (no-arg constructor only), so runtime deps resolve through here.
 * The integrator wires [shuffleAllLibrary] to the data layer at app startup.
 */
object PlaybackGraph {

    /**
     * Whole-library provider used by the SHUFFLE_ALL session command when the
     * PlaybackCatalog is still empty. Returns the full library song list.
     */
    @Volatile
    var shuffleAllLibrary: suspend () -> List<Song> = { emptyList() }

    fun get(context: Context): Graph = Graph(context.applicationContext)

    /** Extension point for the data-layer wiring (repository, prefs…). */
    class Graph(val appContext: Context)
}

/**
 * Media3 MediaSessionService owning the ExoPlayer + MediaSession. UI and car
 * screens talk to it through a MediaController (see [PlayerConnection]);
 * shared observable state is published to [PlayerBus] and the car snapshot
 * lives in AutoPlaybackState (via [AutoStateSync]).
 */
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var ops: ExoPlayerOps
    private lateinit var queueManager: QueueManager
    private lateinit var session: MediaSession
    private lateinit var autoSync: AutoStateSync

    override fun onCreate() {
        super.onCreate()
        // Same channel id/name the previous app used; media3 reuses it when
        // it already exists instead of creating its default "Now playing".
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = android.app.NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Reproducción de 8th",
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Control de reproducción de música"
            manager.createNotificationChannel(channel)
        }
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        ops = ExoPlayerOps(player, this, ArtCache(this), scope)
        queueManager = QueueManager(ops)
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(DEFAULT_CHANNEL_ID)
                .setChannelName(androidx.media3.session.R.string.default_notification_channel_name)
                .build(),
        )
        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
        player.addListener(playerListener)
        autoSync = AutoStateSync(scope, player, queueManager)
        startTicker()
        pushFullState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swipe away with nothing playing: don't linger as a foreground-less
        // service (media3 keeps the session alive otherwise).
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        autoSync.release()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Player events -> QueueManager / PlayerBus ----

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            queueManager.onMediaItemTransition(player.currentMediaItemIndex)
            PlayerBus.setCurrentMediaIndex(
                if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
            )
            PlayerBus.setQueueIndex(queueManager.queueState.index)
            PlayerBus.setDurationMs(ops.durationMs)
            PlayerBus.updatePosition(ops.positionMs)
            // Covers for non-initial queue items are resolved lazily: the
            // notification/lock screen would lose the art when advancing.
            resolveArtworkForCurrent()
            pushFullState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlayerBus.setPlaying(isPlaying)
            if (!isPlaying) {
                // Publish the final position once on pause.
                PlayerBus.updatePosition(ops.positionMs)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // With LoopMode.all/one the player advances/repeats on its own;
            // this fallback is only for LoopMode.off: stop and keep the
            // state consistent for the notification/Auto instead of
            // staying in "completed".
            if (playbackState == Player.STATE_ENDED && queueManager.loopMode == LoopMode.OFF) {
                queueManager.onPlaybackEnded()
                PlayerBus.updatePosition(ops.positionMs)
            }
            PlayerBus.setDurationMs(ops.durationMs)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Deliberately NOT written back into QueueManager (a queue loaded
            // with shuffled=true has native shuffle off + queueShuffled=true;
            // mirroring here would corrupt that flag via feedback loops).
            PlayerBus.setShuffleEnabled(queueManager.queueShuffled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            PlayerBus.setLoopMode(queueManager.loopMode)
        }
    }

    /** 500ms ticker: advances the published position only while playing. */
    private fun startTicker() {
        scope.launch {
            var wasPlaying = false
            while (isActive) {
                delay(500)
                if (player.isPlaying || wasPlaying) {
                    PlayerBus.updatePosition(ops.positionMs)
                }
                wasPlaying = player.isPlaying
            }
        }
    }

    private fun resolveArtworkForCurrent() {
        val song = queueManager.currentSong ?: return
        val current = player.currentMediaItem ?: return
        if (current.mediaId != song.playUri) return
        if (current.mediaMetadata.artworkUri != null) return
        ops.setMediaItemArtwork(song)
    }

    private fun pushFullState() {
        PlayerBus.setQueue(queueManager.queueState.list.toList())
        PlayerBus.setQueueIndex(queueManager.queueState.index)
        PlayerBus.setQueueLabel(queueManager.queueState.label)
        PlayerBus.setShuffleEnabled(queueManager.queueShuffled)
        PlayerBus.setLoopMode(queueManager.loopMode)
        PlayerBus.setPlaying(player.isPlaying)
        PlayerBus.setDurationMs(ops.durationMs)
        PlayerBus.setCurrentMediaIndex(
            if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
        )
        PlayerBus.updatePosition(ops.positionMs)
    }

    // ---- Custom session commands ----

    private val sessionCallback = object : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_PLAY_URIS, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_PLAY_SONGS, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_ADD_NEXT, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_ADD_END, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_REMOVE_AT, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_MOVE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SEEK_INDEX, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SET_SHUFFLE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_CYCLE_LOOP, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_REFRESH_ART, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SHUFFLE_ALL, Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            try {
                handleCustomCommand(customCommand.customAction, args)
            } catch (_: Exception) {
                // A malformed command must never take the service down.
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun handleCustomCommand(action: String, args: Bundle) {
        when (action) {
            ACTION_PLAY_URIS -> {
                args.classLoader = Song::class.java.classLoader
                val uris = args.getStringArrayList(ARG_URIS).orEmpty()
                // Unknown URIs still play (bare Song); richer data is best-effort.
                val songs = uris.map { u ->
                    PlaybackCatalog.get(u) ?: Song(path = u, contentUri = u)
                }
                enqueuePlayQueue(songs, args)
            }

            ACTION_PLAY_SONGS -> {
                args.classLoader = Song::class.java.classLoader
                val songs = args.getParcelableArrayListCompat(ARG_SONGS)
                enqueuePlayQueue(songs, args)
            }

            ACTION_ADD_NEXT -> {
                val song = args.getParcelableSong(ARG_SONG) ?: return
                scope.launch {
                    queueManager.addToQueueNext(song)
                    pushFullState()
                }
            }

            ACTION_ADD_END -> {
                val song = args.getParcelableSong(ARG_SONG) ?: return
                scope.launch {
                    queueManager.addToQueueEnd(song)
                    pushFullState()
                }
            }

            ACTION_REMOVE_AT -> {
                queueManager.removeFromQueue(args.getInt(ARG_INDEX))
                pushFullState()
            }

            ACTION_MOVE -> {
                queueManager.reorderQueue(args.getInt(ARG_FROM), args.getInt(ARG_TO))
                pushFullState()
            }

            ACTION_SEEK_INDEX -> {
                queueManager.seekToIndex(args.getInt(ARG_INDEX))
                pushFullState()
            }

            ACTION_SET_SHUFFLE -> {
                val enable = args.getBoolean(ARG_ENABLE)
                scope.launch {
                    queueManager.toggleShuffle(enable)
                    pushFullState()
                }
            }

            ACTION_CYCLE_LOOP -> {
                queueManager.cycleLoopMode()
                pushFullState()
            }

            ACTION_REFRESH_ART -> resolveArtworkForCurrent()

            ACTION_SHUFFLE_ALL -> shuffleAll()
        }
    }

    private fun enqueuePlayQueue(songs: List<Song>, args: Bundle) {
        val index = args.getInt(ARG_INDEX, 0)
        val label = args.getString(ARG_LABEL) ?: ""
        val autoPlay = args.getBoolean(ARG_AUTO_PLAY, true)
        val shuffled = args.getBoolean(ARG_SHUFFLED, false)
        val resume = args.getBoolean(ARG_RESUME, false)
        scope.launch {
            val failed = queueManager.playQueueList(
                songs,
                initialIndex = index,
                label = label,
                autoPlay = autoPlay,
                shuffled = shuffled,
                resumeIfSameSong = resume,
            )
            PlayerBus.completeResult(failed)
            pushFullState()
            resolveArtworkForCurrent()
        }
    }

    /**
     * "Aleatorio Todo" service-side: shuffles the whole local library without
     * shipping thousands of items over the session (TransactionTooLarge
     * guard from the old EventChannel design).
     */
    private fun shuffleAll() {
        scope.launch {
            val library = PlaybackCatalog.contents().ifEmpty {
                runCatching { PlaybackGraph.shuffleAllLibrary() }.getOrDefault(emptyList())
            }
            if (library.isEmpty()) return@launch
            val current = queueManager.currentSong
            val order = if (current != null) {
                // Keeps the current song first, shuffles the rest.
                QueueManager.shuffledOrderFor(library, current, Random(System.nanoTime()))
            } else {
                library.shuffled(Random(System.nanoTime()))
            }
            val failed = queueManager.playQueueList(
                order,
                initialIndex = 0,
                label = "Aleatorio Todo",
                autoPlay = if (current != null) ops.playing else true,
                resumeIfSameSong = current != null,
                shuffled = true,
            )
            PlayerBus.completeResult(failed)
            pushFullState()
            resolveArtworkForCurrent()
        }
    }

    @Suppress("DEPRECATION")
    private fun Bundle.getParcelableArrayListCompat(key: String): ArrayList<Song> =
        getParcelableArrayList(key) ?: arrayListOf()

    @Suppress("DEPRECATION")
    private fun Bundle.getParcelableSong(key: String): Song? = getParcelable(key)

    companion object {
        const val ACTION_PLAY_URIS = "com.xly.u_8th.PLAY_URIS"
        const val ACTION_PLAY_SONGS = "com.xly.u_8th.PLAY_SONGS"
        const val ACTION_ADD_NEXT = "com.xly.u_8th.ADD_NEXT"
        const val ACTION_ADD_END = "com.xly.u_8th.ADD_END"
        const val ACTION_REMOVE_AT = "com.xly.u_8th.REMOVE_AT"
        const val ACTION_MOVE = "com.xly.u_8th.MOVE"
        const val ACTION_SEEK_INDEX = "com.xly.u_8th.SEEK_INDEX"
        const val ACTION_SET_SHUFFLE = "com.xly.u_8th.SET_SHUFFLE"
        const val ACTION_CYCLE_LOOP = "com.xly.u_8th.CYCLE_LOOP"
        const val ACTION_REFRESH_ART = "com.xly.u_8th.REFRESH_ART"
        const val ACTION_SHUFFLE_ALL = "com.xly.u_8th.SHUFFLE_ALL"

        internal const val ARG_URIS = "uris"
        internal const val ARG_SONGS = "songs"
        internal const val ARG_SONG = "song"
        internal const val ARG_INDEX = "index"
        internal const val ARG_LABEL = "label"
        internal const val ARG_AUTO_PLAY = "autoPlay"
        internal const val ARG_SHUFFLED = "shuffled"
        internal const val ARG_RESUME = "resume"
        internal const val ARG_ENABLE = "enable"
        internal const val ARG_FROM = "from"
        internal const val ARG_TO = "to"

        const val DEFAULT_CHANNEL_ID = "com.xly.u_8th.playback"
    }
}
