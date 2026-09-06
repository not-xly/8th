package com.xly.u_8th.playback

import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song

/**
 * Narrow seam over the underlying player so [QueueManager] stays pure Kotlin
 * (JVM-unit-testable with a fake implementation).
 */
interface PlayerOps {

    /** Loads a fresh queue at [startIndex], resuming at [startPositionMs]. */
    fun replaceItems(songs: List<Song>, startIndex: Int, startPositionMs: Long): Boolean

    fun addItem(song: Song, index: Int): Boolean

    fun removeItemAt(index: Int)

    fun moveItem(from: Int, to: Int)

    fun clearItems()

    /** Jumps to the item at [index] from its start; false when it failed. */
    fun seekToIndex(index: Int): Boolean

    fun seekTo(positionMs: Long)

    fun play()

    fun pause()

    /** End-of-queue handling: rewind and pause (keeps state consistent). */
    fun stopForEnd()

    val playing: Boolean
    val positionMs: Long
    val durationMs: Long

    /** -1 when nothing is loaded. */
    val currentIndex: Int
    val itemCount: Int

    fun setShuffleEnabled(enabled: Boolean)
    val shuffleEnabled: Boolean

    fun setRepeatMode(mode: LoopMode)

    /** Re-publishes the current item's metadata with resolved artwork. */
    fun setMediaItemArtwork(song: Song)
}
