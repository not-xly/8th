package com.xly.u_8th.auto

data class AutoQueueItem(
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long
)

data class AutoPlaybackSnapshot(
    val uri: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val shuffled: Boolean = false,
    val loopMode: Int = 0,
    val queue: List<AutoQueueItem> = emptyList(),
    val queueIndex: Int = 0,
)

object AutoPlaybackState {

    @Volatile
    private var current: AutoPlaybackSnapshot = AutoPlaybackSnapshot()

    private val sinks = java.util.concurrent.CopyOnWriteArraySet<(AutoPlaybackSnapshot) -> Unit>()

    fun current(): AutoPlaybackSnapshot = current

    fun update(snapshot: AutoPlaybackSnapshot) {
        current = snapshot
        sinks.forEach { sink -> runCatching { sink(snapshot) } }
    }

    fun attach(sink: (AutoPlaybackSnapshot) -> Unit) {
        sinks.add(sink)
        sink(current)
    }

    fun detach(sink: (AutoPlaybackSnapshot) -> Unit) {
        sinks.remove(sink)
    }
}