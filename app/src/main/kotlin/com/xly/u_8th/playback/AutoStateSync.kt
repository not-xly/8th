package com.xly.u_8th.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.xly.u_8th.auto.AutoPlaybackState
import com.xly.u_8th.auto.AutoQueueItem
import com.xly.u_8th.auto.AutoPlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publishes playback snapshots to the car via [AutoPlaybackState], with the
 * change-detection port of the Flutter controller's _pushAutoSnapshot:
 * a 1s tick plus a cheap signature — only pushed when the state changed, the
 * position advanced (>=2s while playing), or a 10s playing heartbeat fired.
 * Before, the whole queue was serialized every 500ms even while paused.
 *
 * Native-to-native now: the queue always travels (no binder cost) and the
 * queue hash is only recomputed when queueRevision changes (instead of
 * walking every uri each second).
 */
class AutoStateSync(
    scope: CoroutineScope,
    private val player: Player,
    private val queueManager: QueueManager,
) {

    private var lastKey = ""
    private var lastPosSec = -1
    private var lastPushElapsed = 0L
    private var lastQueueRevision = -1
    private var cachedQueueHash = 0x811c9dc5L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            push()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            push()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            push()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            push()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            push()
        }
    }

    init {
        player.addListener(listener)
        scope.launch {
            while (isActive) {
                delay(1_000)
                push()
            }
        }
    }

    fun release() {
        player.removeListener(listener)
    }

    /** Computes the snapshot and updates [AutoPlaybackState] only on change. */
    fun push() {
        try {
            val song = queueManager.currentSong ?: return
            val list = queueManager.queueState.list
            val playing = player.isPlaying
            val loopWire = queueManager.loopMode.wire
            val queueIndex = queueManager.queueState.index

            // Cheap signature without serializing titles: current uri + flags
            // + length + FNV-1a over uri LENGTHS (no string traversal);
            // recomputed only when the queue mutates (queueRevision).
            if (lastQueueRevision != queueManager.queueRevision) {
                var hash = 0x811c9dc5L
                for (s in list) {
                    hash = hash xor s.playUri.length.toLong()
                    hash = (hash * 0x01000193L) and 0xFFFFFFFFL
                }
                cachedQueueHash = hash
                lastQueueRevision = queueManager.queueRevision
            }
            val key = "${song.playUri}|$playing|${queueManager.queueShuffled}|$loopWire" +
                "|$queueIndex|${list.size}|$cachedQueueHash"

            val posSec = (player.currentPosition / 1000).toInt()
            val now = SystemClock.elapsedRealtime()
            val posChanged = (posSec - lastPosSec).let { if (it < 0) -it else it } >= 2
            val keyChanged = key != lastKey
            // Heartbeat ONLY while playing: on pause the car already has the
            // state and a 10s push would wake the CPU forever.
            val heartbeat = playing && now - lastPushElapsed >= 10_000
            if (!keyChanged && !(playing && posChanged) && !heartbeat) {
                return
            }
            lastKey = key
            lastPosSec = posSec
            lastPushElapsed = now

            AutoPlaybackState.update(
                AutoPlaybackSnapshot(
                    uri = song.playUri,
                    title = song.displayTitle,
                    artist = song.artist ?: "",
                    album = song.album ?: "",
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = if (player.duration == C.TIME_UNSET) 0L else player.duration,
                    playing = playing,
                    shuffled = queueManager.queueShuffled,
                    loopMode = loopWire,
                    queue = list.map {
                        AutoQueueItem(
                            uri = it.playUri,
                            title = it.displayTitle,
                            artist = it.artist,
                            album = it.album,
                            durationMs = it.durationMs ?: 0L,
                        )
                    },
                    queueIndex = queueIndex,
                ),
            )
        } catch (_: Exception) {
            // Snapshot failures must never take the playback service down.
        }
    }
}
