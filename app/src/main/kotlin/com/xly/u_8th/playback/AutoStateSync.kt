package com.xly.u_8th.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import com.xly.u_8th.auto.AutoPlaybackState
import com.xly.u_8th.auto.AutoQueueItem
import com.xly.u_8th.auto.AutoPlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class AutoStateSync(
    private val scope: CoroutineScope,
    private val player: Player,
    private val queueManager: QueueManager,
) {
    private val cache = AutoSnapshotCache()
    private var lastSnapshot: AutoPlaybackSnapshot? = null
    private var ticker: Job? = null
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateTicker()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // `push()` ya compara con el snapshot anterior y descarta lo
            // que no cambió: `forcePosition = true` lo convertía en una
            // publicación en CADA evento del player, con reconstrucción
            // completa del ItemList en el host del coche. Excepción: un
            // salto de posición (seek), que con el player en pausa no se
            // publicaba nunca y el coche mostraba la posición vieja.
            push(forcePosition = events.contains(Player.EVENT_POSITION_DISCONTINUITY))
        }
    }

    init {
        player.addListener(listener)
        updateTicker()
        push()
    }

    private fun updateTicker() {
        ticker?.cancel()
        ticker = if (player.isPlaying) scope.launch {
            while (isActive && player.isPlaying) {
                delay(1_000)
                push()
            }
        } else null
    }

    fun release() {
        ticker?.cancel()
        player.removeListener(listener)
    }

    /**
     * Publica el snapshot al host del coche solo cuando algo cambió de
   * verdad. Dos guardas:
     * - `changed`: cualquier campo distinto de la posición (pista, cola,
     *   orden, repeat…).
     * - posición: solo se propaga cada [POSITION_EAGER_MS] mientras suena;
     *   `PlaybackScreen` la extrapola sola con su ticker de 1 s, así que
     *   publicar cada evento del player solo generaba trabajo inútil.
     */
    fun push(forcePosition: Boolean = false) {
        val snapshot = cache.snapshot(
            queueManager,
            player.currentPosition,
            if (player.duration == C.TIME_UNSET) 0L else player.duration,
            player.isPlaying,
        )
        val previous = lastSnapshot
        if (previous != null && previous == snapshot) return
        val changed = previous == null || previous.copy(positionMs = snapshot.positionMs) != snapshot
        val positionChanged = previous == null ||
            abs(previous.positionMs - snapshot.positionMs) >= POSITION_EAGER_MS
        if (!changed && !forcePosition && !(snapshot.playing && positionChanged)) return
        lastSnapshot = snapshot
        AutoPlaybackState.update(snapshot)
    }

    private companion object {
        const val POSITION_EAGER_MS = 2_000L
    }
}

class AutoSnapshotCache {
    private var revision = -1
    private var queue = emptyList<AutoQueueItem>()

    fun snapshot(manager: QueueManager, positionMs: Long, durationMs: Long, playing: Boolean): AutoPlaybackSnapshot {
        if (revision != manager.queueRevision) {
            queue = manager.queueState.list.map {
                AutoQueueItem(it.playUri, it.displayTitle, it.artist, it.album, it.durationMs ?: 0L)
            }
            revision = manager.queueRevision
        }
        val song = manager.currentSong
        return AutoPlaybackSnapshot(
            uri = song?.playUri.orEmpty(),
            title = song?.displayTitle.orEmpty(),
            artist = song?.artist.orEmpty(),
            album = song?.album.orEmpty(),
            positionMs = if (song == null) 0L else positionMs.coerceAtLeast(0L),
            durationMs = if (song == null) 0L else durationMs.coerceAtLeast(0L),
            playing = song != null && playing,
            shuffled = manager.queueShuffled,
            loopMode = manager.loopMode.wire,
            queue = queue,
            queueIndex = manager.queueState.index,
        )
    }
}
