package com.xly.u_8th.playback

import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import kotlin.random.Random

/**
 * Port of the Dart AudioPlayerService queue logic (lib/src/services/
 * audio_player_service.dart). Pure Kotlin: all player access goes through
 * [PlayerOps] so the class is JVM-unit-testable.
 */
class QueueManager(private val player: PlayerOps) {

    /** Mutable in place (like the Dart QueueState): views that already hold
     *  the reference see changes on the next emit instead of keeping the
     *  old list. */
    class QueueState(var list: MutableList<Song>, var index: Int, var label: String)

    val queueState = QueueState(mutableListOf(), 0, "")

    /** Physical order the queue had before any shuffle ("Aleatorio Todo"). */
    var originalOrder: List<String> = emptyList()

    /** true while the loaded queue is shuffled (natively or physically). */
    var queueShuffled: Boolean = false

    var loopMode: LoopMode = LoopMode.ALL

    /**
     * Bumped on every queue mutation (set/insert/remove/move/shuffle). State
     * syncs recompute their cheap queue hash only when this changes.
     */
    var queueRevision: Int = 0
        private set

    val currentSong: Song?
        get() {
            val list = queueState.list
            return if (list.isNotEmpty() && queueState.index in list.indices) {
                list[queueState.index]
            } else {
                null
            }
        }

    private val random = Random(System.nanoTime())

    /** Shuffle order of queue positions; see [_skip]. */
    private var shuffleOrder: List<Int> = emptyList()
    private var shuffleOrderRevision = -1

    private fun touchQueue() {
        queueRevision++
    }

    /**
     * Loads a new queue. Returns how many songs failed to load (kept at 0:
     * ExoPlayer resolves URIs lazily, so there is no open step that can drop
     * items and misalign the index, unlike the just_audio port).
     */
    suspend fun playQueueList(
        songs: List<Song>,
        initialIndex: Int = 0,
        label: String,
        autoPlay: Boolean = true,
        resumeIfSameSong: Boolean = false,
        shuffled: Boolean = false,
        currentSongBefore: Song? = null,
    ): Int {
        if (songs.isEmpty()) return 0

        val prevPath = currentSongBefore?.playUri ?: currentSong?.playUri
        val clamped = initialIndex.coerceIn(0, songs.size - 1)

        val sameSong = resumeIfSameSong && prevPath != null && songs[clamped].playUri == prevPath
        val initialPos = if (sameSong) player.positionMs else 0L

        // All songs are kept here; the index never shifts (see kdoc).
        val keptSongs = songs
        val keptIndex = clamped
        val failed = 0

        // Snapshot for rollback: if the player load fails, the UI must not be
        // left diverged from the player (before, queueState was updated first
        // and the catch only logged).
        val prevList = queueState.list
        val prevIndex = queueState.index
        val prevLabel = queueState.label
        val prevOrder = originalOrder
        val prevShuffled = queueShuffled
        queueState.list = keptSongs.toMutableList()
        queueState.index = keptIndex
        queueState.label = label
        touchQueue()
        if (!shuffled) {
            originalOrder = keptSongs.map { it.playUri }
        }
        queueShuffled = shuffled

        val replaced = try {
            player.replaceItems(queueState.list, keptIndex, initialPos)
        } catch (_: Exception) {
            false
        }
        if (!replaced) {
            // Roll back to the previous state to keep UI vs player consistent.
            queueState.list = prevList
            queueState.index = prevIndex
            queueState.label = prevLabel
            originalOrder = prevOrder
            queueShuffled = prevShuffled
            return songs.size
        }
        // New queue = intentional physical order: native shuffle goes off
        // (whoever un-shuffles after a physically shuffled queue expects the
        // list order).
        player.setShuffleEnabled(false)
        regenerateShuffleOrder(queueState.list.size)
        player.setRepeatMode(loopMode)
        if (autoPlay) {
            player.play()
        }
        return failed
    }

    fun playNext() = _skip(1)

    fun playPrevious() = _skip(-1)

    /**
     * Robust skip for rapid taps. The destination is computed from
     * [QueueState.index] (updated optimistically here) and NOT from
     * player.currentIndex, which does not change until the previous seek
     * finishes loading: before, several rapid "next" taps recomputed the
     * same destination and title/art never advanced.
     */
    private fun _skip(direction: Int) {
        val n = queueState.list.size
        if (n == 0) return
        var target = skipTargetFor(queueState.index, n, direction)
        // Native shuffle active: walk the player's random order.
        if (player.shuffleEnabled) {
            val order = ensureShuffleOrder(n)
            val pos = order.indexOf(queueState.index)
            if (pos >= 0) {
                var nextPos = pos + direction
                if (nextPos < 0) nextPos = n - 1
                if (nextPos >= n) nextPos = 0
                target = order[nextPos]
            }
        }
        queueState.index = target
        if (player.itemCount == 0) return
        val ok = try {
            player.seekToIndex(target)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            // Seek failed (broken file…): fall back to the player's real
            // index so the UI doesn't point at a song that isn't playing.
            val real = player.currentIndex
            if (real >= 0 && real < queueState.list.size) {
                queueState.index = real
            }
        }
    }

    suspend fun addToQueueNext(song: Song): Boolean {
        if (queueState.list.isEmpty() || player.itemCount == 0) {
            playQueueList(listOf(song), initialIndex = 0, label = "Cola personalizada")
            return true
        }
        val insertPos = (queueState.index + 1).coerceIn(0, queueState.list.size)
        val added = try {
            player.addItem(song, insertPos)
        } catch (_: Exception) {
            false
        }
        if (!added) return false
        queueState.list.add(insertPos, song)
        touchQueue()
        return true
    }

    suspend fun addToQueueEnd(song: Song): Boolean {
        if (queueState.list.isEmpty() || player.itemCount == 0) {
            playQueueList(listOf(song), initialIndex = 0, label = "Cola personalizada")
            return true
        }
        val insertPos = queueState.list.size
        val added = try {
            player.addItem(song, insertPos)
        } catch (_: Exception) {
            false
        }
        if (!added) return false
        queueState.list.add(song)
        touchQueue()
        return true
    }

    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= queueState.list.size) return
        val removingCurrent = index == queueState.index
        if (player.itemCount == 0) {
            queueState.list.removeAt(index)
            if (queueState.list.isEmpty()) {
                resetQueueState()
            } else if (index < queueState.index) {
                queueState.index--
            } else if (removingCurrent) {
                queueState.index = queueState.index.coerceIn(0, queueState.list.size - 1)
            }
            touchQueue()
            // Keeps originalOrder coherent (stable playUri keys).
            pruneOriginalOrder()
            return
        }
        if (queueState.list.size == 1) {
            player.stopForEnd()
            player.clearItems()
            resetQueueState()
            touchQueue()
            return
        }
        player.removeItemAt(index)
        queueState.list.removeAt(index)
        touchQueue()
        if (index < queueState.index) {
            queueState.index--
        } else if (removingCurrent) {
            // The player auto-advances to the next item; sync with the real
            // player index instead of clamping blindly.
            val real = player.currentIndex
            queueState.index = if (real in queueState.list.indices) {
                real
            } else {
                queueState.index.coerceIn(0, queueState.list.size - 1)
            }
        }
        pruneOriginalOrder()
    }

    fun reorderQueue(oldIndex: Int, newIndex: Int) {
        if (oldIndex < 0 || oldIndex >= queueState.list.size) return
        if (newIndex < 0 || newIndex > queueState.list.size) return
        // newIndex arrives as the post-removal position (may equal length for
        // an append): adjust before using it.
        var adjusted = newIndex
        if (oldIndex < adjusted) adjusted -= 1
        if (oldIndex == adjusted) return
        val currentUri = currentSong?.playUri
        if (player.itemCount == 0) {
            val item = queueState.list.removeAt(oldIndex)
            queueState.list.add(adjusted, item)
            touchQueue()
            // Re-find the current song's index by uri (robust, avoids
            // off-by-one).
            if (currentUri != null) {
                val newIdx = queueState.list.indexOfFirst { it.playUri == currentUri }
                if (newIdx != -1) queueState.index = newIdx
            }
            return
        }
        player.moveItem(oldIndex, adjusted)
        val item = queueState.list.removeAt(oldIndex)
        queueState.list.add(adjusted, item)
        touchQueue()
        if (currentUri != null) {
            val newIdx = queueState.list.indexOfFirst { it.playUri == currentUri }
            if (newIdx != -1) queueState.index = newIdx
        }
    }

    /**
     * Enables the player's NATIVE shuffle: next/prev follow the random order
     * without rebuilding the playlist, so there is no reload hiccup on the
     * current song. [QueueState.list] order does NOT change: the queue view
     * keeps showing the original order (standard player behavior).
     */
    fun shuffleAllQueue(): Int {
        if (queueState.list.size <= 1) return 0
        if (player.itemCount == 0) {
            // No player loaded (tests / transient state): flag only.
            queueShuffled = true
            return 0
        }
        try {
            // Keeps the current song as current and shuffles the rest.
            player.setShuffleEnabled(true)
        } catch (_: Exception) {
        }
        regenerateShuffleOrder(queueState.list.size)
        queueShuffled = true
        return 0
    }

    suspend fun restoreOriginalOrder(): Int {
        if (!queueShuffled || queueState.list.isEmpty()) return 0
        // Native shuffle: turning it off restores the original order
        // instantly, without reloading anything (the list was never
        // physically reordered).
        if (player.shuffleEnabled) {
            try {
                player.setShuffleEnabled(false)
            } catch (_: Exception) {
            }
            queueShuffled = false
            regenerateShuffleOrder(queueState.list.size)
            return 0
        }
        // Physically shuffled queue (e.g. after "Aleatorio Todo"): rebuild
        // the original order (same behavior as before, includes a reload).
        if (originalOrder.isEmpty()) return 0
        val (restored, idx) = restoredOrderFor(queueState.list, originalOrder, currentSong?.playUri)
        if (restored.isEmpty()) return 0
        val failed = playQueueList(
            restored,
            initialIndex = idx,
            label = queueState.label,
            autoPlay = player.playing,
            resumeIfSameSong = true,
            currentSongBefore = currentSong,
        )
        queueShuffled = false
        return failed
    }

    suspend fun toggleShuffle(enable: Boolean) {
        if (enable) {
            shuffleAllQueue()
        } else {
            restoreOriginalOrder()
        }
    }

    /**
     * Cycle order as in the Dart app: off→all, all→one, one→off, i.e.
     * starting from ALL it goes ALL→ONE→OFF→ALL.
     */
    fun cycleLoopMode(): LoopMode {
        loopMode = when (loopMode) {
            LoopMode.OFF -> LoopMode.ALL
            LoopMode.ALL -> LoopMode.ONE
            LoopMode.ONE -> LoopMode.OFF
        }
        player.setRepeatMode(loopMode)
        return loopMode
    }

    fun ensureLoopAll() {
        if (loopMode != LoopMode.ALL) {
            loopMode = LoopMode.ALL
            player.setRepeatMode(loopMode)
        }
    }

    fun seekToIndex(index: Int) {
        if (index < 0 || index >= queueState.list.size) return
        queueState.index = index
        player.seekToIndex(index)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    /** Keeps [QueueState.index] synced from the player (service listener). */
    fun onMediaItemTransition(index: Int) {
        if (index in queueState.list.indices && queueState.index != index) {
            queueState.index = index
        }
    }

    /** Queue finished in LoopMode.OFF: rewind + pause, consistent state. */
    fun onPlaybackEnded() {
        player.stopForEnd()
    }

    private fun resetQueueState() {
        queueState.list.clear()
        queueState.index = 0
        queueState.label = ""
        originalOrder = emptyList()
        queueShuffled = false
    }

    private fun pruneOriginalOrder() {
        originalOrder = originalOrder.filter { p -> queueState.list.any { it.playUri == p } }
    }

    /** Shuffle walk order: current position first, Fisher-Yates on the rest. */
    private fun ensureShuffleOrder(n: Int): List<Int> {
        if (shuffleOrderRevision == queueRevision && shuffleOrder.size == n) return shuffleOrder
        regenerateShuffleOrder(n)
        return shuffleOrder
    }

    private fun regenerateShuffleOrder(n: Int) {
        shuffleOrderRevision = queueRevision
        if (n <= 0) {
            shuffleOrder = emptyList()
            return
        }
        val current = queueState.index.coerceIn(0, n - 1)
        shuffleOrder = listOf(current) + (0 until n).filter { it != current }.shuffled(random)
    }

    companion object {

        /**
         * Pure skip math (testable): target = (index + direction) mod n,
         * adding n when negative so prev at index 0 wraps to n-1.
         */
        fun skipTargetFor(index: Int, n: Int, direction: Int): Int {
            if (n <= 0) return index
            var target = (index + direction) % n
            if (target < 0) target += n
            return target
        }

        /**
         * Pure shuffle logic (testable without a player): current first,
         * rest randomized. Port of AudioPlayerService.shuffledOrderFor.
         */
        fun shuffledOrderFor(list: List<Song>, current: Song, rng: Random): List<Song> {
            val rest = list.filter { it.playUri != current.playUri }.shuffled(rng)
            return listOf(current) + rest
        }

        /**
         * Pure restore logic: orders songs according to [orderUris] and
         * appends the songs added after the shuffle. Returns
         * (restored list, index of the current song).
         */
        fun restoredOrderFor(
            list: List<Song>,
            orderUris: List<String>,
            currentUri: String?,
        ): Pair<List<Song>, Int> {
            val byUri = list.associateBy { it.playUri }
            val restored = orderUris.mapNotNull { byUri[it] }.toMutableList()
            for (song in list) {
                if (restored.none { it.playUri == song.playUri }) restored.add(song)
            }
            val idx = restored.indexOfFirst { it.playUri == currentUri }.let { if (it < 0) 0 else it }
            return Pair(restored, idx)
        }
    }
}
