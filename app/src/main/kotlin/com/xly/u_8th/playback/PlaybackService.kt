package com.xly.u_8th.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.xly.u_8th.EighthApplication

/**
 * Minimal service dependency seam. MediaSessionService is instantiated by the
 * system (no-arg constructor only), so runtime deps resolve through here.
 * The integrator wires [shuffleAllLibrary] to the data layer at app startup.
 */
object PlaybackGraph {

    @Volatile
    var shuffleAllLibrary: suspend () -> List<Song> = { emptyList() }
}

/**
 * Media3 MediaSessionService owning the ExoPlayer + MediaSession. UI and car
 * screens talk to it through a MediaController (see [PlayerConnection]);
 * shared observable state is published to [PlayerBus] and the car snapshot
 * lives in AutoPlaybackState (via [AutoStateSync]).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var ops: ExoPlayerOps
    private lateinit var queueManager: QueueManager
    private lateinit var session: MediaSession
    private lateinit var autoSync: AutoStateSync
    private lateinit var sessionStore: PlaybackSessionStore
    private lateinit var sessionSaver: SessionSaveScheduler
    private val restoreGate = RestoreGate()
    private var restoreJob: Job? = null
    private var libraryJob: Job? = null
    private var errorJob: Job? = null
    private var ticker: Job? = null
    private var sessionReady = false
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        // Same channel id/name the previous app used; media3 reuses it when
        // it already exists instead of creating its default "Now playing".
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
        ops = ExoPlayerOps(player, ArtCache(this), scope)
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
        sessionStore = EighthApplication.get(this).playbackSessionStore
        sessionSaver = EighthApplication.get(this).playbackSessionSaver
        pushFullState()
        restoreSession()
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
        if (sessionReady) {
            // Immediate y con el valor ya tomado: onDestroy no puede esperar
            // al debounce, así que la instantánea se calcula ahora.
            val snapshot = queueManager.snapshot()
            sessionSaver.schedule(immediate = true) { snapshot }
        }
        destroying = true
        scope.cancel()
        player.removeListener(playerListener)
        autoSync.release()
        session.release()
        player.release()
        PlayerBus.setPlaying(false)
        com.xly.u_8th.auto.AutoPlaybackState.update(
            com.xly.u_8th.auto.AutoPlaybackState.current().copy(playing = false),
        )
        super.onDestroy()
    }

    private fun restoreSession() {
        val revision = restoreGate.revision
        restoreJob = scope.launch {
            try {
                val saved = sessionSaver.latest ?: sessionStore.load()
                val readable = saved?.let { sessionStore.readableSession(it) }
                if (!restoreGate.accepts(revision)) return@launch
                // Red de seguridad por el deprecado
                // onPlayerCommandRequest: si un controlador ya
                // envió un comando mientras se leía la sesión
                // (un media3 futuro puede dejar de invocar el
                // callback), el player ya no está vacío y la
                // restauración pisaría la acción del usuario.
                val playerWasControlled =
                    player.mediaItemCount > 0 || player.currentPosition > 0
                if (readable != null && !playerWasControlled) {
                    queueManager.restoreSession(readable)
                    val missing = (saved?.songs?.size ?: 0) - readable.songs.size
                    if (missing > 0) PlayerBus.completeResult(missing)
                }
                sessionReady = true
                pushFullState()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (restoreGate.accepts(revision)) sessionReady = true
            }
        }
    }

    private fun newCommand() {
        restoreGate.command()
        restoreJob?.cancel()
        libraryJob?.cancel()
        errorJob?.cancel()
        sessionReady = true
        queueManager.resetErrorRecovery()
    }

    private fun saveSession() {
        // El snapshot se evalúa dentro del job, tras el debounce.
        if (sessionReady && !destroying) sessionSaver.schedule { queueManager.snapshot() }
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
            PlayerBus.updatePosition(ops.positionMs)
            ticker?.cancel()
            if (isPlaying) startTicker()
            saveSession()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            queueManager.onMediaItemTransition(player.currentMediaItemIndex)
            PlayerBus.setQueueIndex(queueManager.queueState.index)
            PlayerBus.updatePosition(ops.positionMs)
            saveSession()
        }

        override fun onPlayerError(error: PlaybackException) {
            PlayerBus.completeResult(1)
            val index = player.currentMediaItemIndex
            val revision = restoreGate.revision
            val queueRevision = queueManager.queueRevision
            val continuePlaying = player.playWhenReady
            errorJob?.cancel()
            errorJob = scope.launch {
                delay(1)
                if (!restoreGate.accepts(revision) || queueManager.queueRevision != queueRevision) return@launch
                queueManager.onPlaybackError(index, continuePlaying)
                pushFullState()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (!destroying) saveSession()
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

    private fun startTicker() {
        ticker = scope.launch {
            var ticks = 0
            while (isActive && player.isPlaying) {
                delay(500)
                PlayerBus.updatePosition(ops.positionMs)
                if (++ticks % 10 == 0) saveSession()
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

    /** Última revisión de cola publicada en [PlayerBus]. */
    private var pushedQueueRevision = -1

    private fun pushFullState() {
        // La cola solo cambia con su revisión (touchQueue):
        // copiarla (O(n)) en CADA transición de tema o
        // discontinuidad de posición era trabajo innecesario;
        // los escalares de abajo son O(1).
        val revision = queueManager.queueRevision
        if (revision != pushedQueueRevision) {
            pushedQueueRevision = revision
            PlayerBus.setQueue(queueManager.queueState.list.toList())
            PlayerBus.setQueueLabel(queueManager.queueState.label)
        }
        PlayerBus.setQueueIndex(queueManager.queueState.index)
        PlayerBus.setShuffleEnabled(queueManager.queueShuffled)
        PlayerBus.setLoopMode(queueManager.loopMode)
        PlayerBus.setPlaying(player.isPlaying)
        PlayerBus.setDurationMs(ops.durationMs)
        PlayerBus.setCurrentMediaIndex(
            if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
        )
        PlayerBus.updatePosition(ops.positionMs)
        if (::autoSync.isInitialized) autoSync.push()
        saveSession()
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
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                    .remove(Player.COMMAND_SET_REPEAT_MODE)
                    .build(),
            )
        }

        // Deprecado en media3 1.8 (sustituido por la negociación
        // de comandos en onConnect), pero aún es el punto de
        // entrada real de "el usuario tocó algo": cancela la
        // restauración y limpia la recuperación de errores.
        // `restoreSession` no depende solo de él (comprueba
        // que el player siga vacío) por si una versión futura
        // deja de invocarlo.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            newCommand()
            return SessionResult.RESULT_SUCCESS
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val code = try {
                if (handleCustomCommand(customCommand.customAction, args)) {
                    SessionResult.RESULT_SUCCESS
                } else {
                    SessionResult.RESULT_ERROR_BAD_VALUE
                }
            } catch (_: Exception) {
                // A malformed command must never take the service down; the
                // error code lets the sender tell the user.
                SessionResult.RESULT_ERROR_UNKNOWN
            }
            return Futures.immediateFuture(SessionResult(code))
        }
    }

    /** false = argumentos inválidos (p. ej. token de cola caducado). */
    private fun handleCustomCommand(action: String, args: Bundle): Boolean {
        if (action != ACTION_REFRESH_ART) newCommand()
        when (action) {
            ACTION_PLAY_URIS -> {
                // La cola la deja PlayerConnection en el catálogo; un token
                // desconocido (otra app, o ya consumido) no reproduce nada.
                val songs = args.getString(ARG_QUEUE_TOKEN)?.let(PlaybackCatalog::takeStaged)
                if (songs.isNullOrEmpty()) return false
                enqueuePlayQueue(songs, args)
            }

            ACTION_ADD_NEXT -> {
                val song = args.songFromUri() ?: return false
                scope.launch {
                    queueManager.addToQueueNext(song)
                    pushFullState()
                }
            }

            ACTION_ADD_END -> {
                val song = args.songFromUri() ?: return false
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
        return true
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
        val revision = restoreGate.revision
        libraryJob = scope.launch {
            val library = try {
                withContext(Dispatchers.IO) { PlaybackGraph.shuffleAllLibrary() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PlayerBus.completeResult(1)
                return@launch
            }
            if (!restoreGate.accepts(revision) || library.isEmpty()) return@launch
            val current = queueManager.currentSong
            val currentIndex = library.indexOfFirst { it.playUri == current?.playUri }
            val failed = queueManager.playQueueList(
                library,
                initialIndex = if (currentIndex >= 0) currentIndex else kotlin.random.Random.nextInt(library.size),
                label = "Aleatorio Todo",
                autoPlay = if (current != null) player.playWhenReady else true,
                resumeIfSameSong = currentIndex >= 0,
                shuffled = true,
            )
            PlayerBus.completeResult(failed)
            pushFullState()
            resolveArtworkForCurrent()
        }
    }

    private fun Bundle.songFromUri(): Song? {
        val uri = getString(ARG_URI)?.takeIf { it.isNotEmpty() } ?: return null
        return PlaybackCatalog.get(uri) ?: Song(path = uri, contentUri = uri)
    }

    companion object {
        const val ACTION_PLAY_URIS = "com.xly.u_8th.PLAY_URIS"
        const val ACTION_ADD_NEXT = "com.xly.u_8th.ADD_NEXT"
        const val ACTION_ADD_END = "com.xly.u_8th.ADD_END"
        const val ACTION_REMOVE_AT = "com.xly.u_8th.REMOVE_AT"
        const val ACTION_MOVE = "com.xly.u_8th.MOVE"
        const val ACTION_SEEK_INDEX = "com.xly.u_8th.SEEK_INDEX"
        const val ACTION_SET_SHUFFLE = "com.xly.u_8th.SET_SHUFFLE"
        const val ACTION_CYCLE_LOOP = "com.xly.u_8th.CYCLE_LOOP"
        const val ACTION_REFRESH_ART = "com.xly.u_8th.REFRESH_ART"
        const val ACTION_SHUFFLE_ALL = "com.xly.u_8th.SHUFFLE_ALL"

        internal const val ARG_QUEUE_TOKEN = "queueToken"
        internal const val ARG_URI = "uri"
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
