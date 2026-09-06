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
    val queueIndex: Int = 0
)

object AutoPlaybackState {

    @Volatile
    private var current: AutoPlaybackSnapshot = AutoPlaybackSnapshot()

    @Volatile
    private var sink: ((AutoPlaybackSnapshot) -> Unit)? = null

    fun current(): AutoPlaybackSnapshot = current

    fun update(snapshot: AutoPlaybackSnapshot) {
        current = snapshot
        sink?.invoke(snapshot)
    }

    fun attach(sink: (AutoPlaybackSnapshot) -> Unit) {
        this.sink = sink
    }

    fun detach() {
        this.sink = null
    }
}