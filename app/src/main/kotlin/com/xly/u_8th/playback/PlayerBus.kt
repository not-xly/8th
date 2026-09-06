package com.xly.u_8th.playback

import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Result of a [QueueManager.playQueueList] round-trip. [seq] increments on
 * every completion so observers can react to each event, even when the
 * payload is identical to the previous one.
 */
data class PlaybackResult(val seq: Int = 0, val failed: Int = 0)

/**
 * Process-wide observable playback state. The service is the only writer
 * (via the internal setters); UI and car screens read the [StateFlow]s.
 */
object PlayerBus {

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    private val _queueIndex = MutableStateFlow(0)
    val queueIndex: StateFlow<Int> = _queueIndex

    private val _queueLabel = MutableStateFlow("")
    val queueLabel: StateFlow<String> = _queueLabel

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _loopMode = MutableStateFlow(LoopMode.ALL)
    val loopMode: StateFlow<LoopMode> = _loopMode

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _currentMediaIndex = MutableStateFlow(-1)
    val currentMediaIndex: StateFlow<Int> = _currentMediaIndex

    private val _lastResult = MutableStateFlow(PlaybackResult())
    val lastResult: StateFlow<PlaybackResult> = _lastResult

    val currentSong: Song?
        get() = _queue.value.getOrNull(_queueIndex.value)

    /** Position-only update (cheap, called from the 500ms ticker). */
    fun updatePosition(ms: Long) {
        _positionMs.value = ms
    }

    // ---- Internal setters: only PlaybackService writes here. ----

    internal fun setQueue(value: List<Song>) {
        _queue.value = value
    }

    internal fun setQueueIndex(value: Int) {
        _queueIndex.value = value
    }

    internal fun setQueueLabel(value: String) {
        _queueLabel.value = value
    }

    internal fun setPlaying(value: Boolean) {
        _playing.value = value
    }

    internal fun setShuffleEnabled(value: Boolean) {
        _shuffleEnabled.value = value
    }

    internal fun setLoopMode(value: LoopMode) {
        _loopMode.value = value
    }

    internal fun setDurationMs(value: Long) {
        _durationMs.value = value
    }

    internal fun setCurrentMediaIndex(value: Int) {
        _currentMediaIndex.value = value
    }

    /** Publishes a finished playQueueList event; bumps [PlaybackResult.seq]. */
    internal fun completeResult(failed: Int) {
        val next = PlaybackResult(seq = _lastResult.value.seq + 1, failed = failed)
        _lastResult.value = next
    }
}
