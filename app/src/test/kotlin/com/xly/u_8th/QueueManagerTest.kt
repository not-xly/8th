package com.xly.u_8th

import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlayerOps
import com.xly.u_8th.playback.QueueManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Port of test/audio_player_service_test.dart (queue logic group) against
 * [QueueManager] with an in-memory [PlayerOps] fake.
 */
class QueueManagerTest {

    // ---- Fake player ----

    /** No android deps: mirrors just enough of the player for the tests. */
    private class FakePlayerOps : PlayerOps {
        val items = mutableListOf<Song>()
        val seeksToIndex = mutableListOf<Int>()
        val artRequests = mutableListOf<Song>()

        var failReplace = false
        var playRequests = 0
        var stoppedForEnd = false
        var repeatModeField = LoopMode.ALL

        override fun replaceItems(songs: List<Song>, startIndex: Int, startPositionMs: Long): Boolean {
            if (failReplace) return false
            items.clear()
            items.addAll(songs)
            return true
        }

        override fun addItem(song: Song, index: Int): Boolean {
            items.add(index.coerceIn(0, items.size), song)
            return true
        }

        override fun removeItemAt(index: Int) {
            if (index in items.indices) items.removeAt(index)
        }

        override fun moveItem(from: Int, to: Int) {
            if (from !in items.indices) return
            val item = items.removeAt(from)
            items.add(to.coerceIn(0, items.size), item)
        }

        override fun clearItems() {
            items.clear()
        }

        override fun seekToIndex(index: Int): Boolean {
            if (index !in items.indices) return false
            seeksToIndex.add(index)
            return true
        }

        override fun seekTo(positionMs: Long) {
            positionMsField = positionMs
        }

        override fun play() {
            playRequests++
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun stopForEnd() {
            stoppedForEnd = true
            playing = false
            positionMsField = 0
        }

        override var playing: Boolean = false

        var positionMsField: Long = 0L

        override val positionMs: Long get() = positionMsField

        override val durationMs: Long get() = 0L

        override var currentIndex: Int = -1

        override val itemCount: Int get() = items.size

        private var shuffleField = false

        override val shuffleEnabled: Boolean get() = shuffleField

        override fun setShuffleEnabled(enabled: Boolean) {
            shuffleField = enabled
        }

        override fun setRepeatMode(mode: LoopMode) {
            repeatModeField = mode
        }

        override fun setMediaItemArtwork(song: Song) {
            artRequests.add(song)
        }
    }

    // ---- Helpers ----

    private fun song(path: String) = Song(path = path, title = path)

    /** Sets queue state WITHOUT loading the player (fake itemCount stays 0),
     *  exactly like the Dart tests that pre-date the loaded playlist. */
    private fun QueueManager.setState(songs: List<Song>, index: Int, label: String = "Test") {
        queueState.list = songs.toMutableList()
        queueState.index = index
        queueState.label = label
        originalOrder = songs.map { it.playUri }
        queueShuffled = false
    }

    // ---- playQueueList ----

    @Test
    fun playQueueList_clampsInitialIndex() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        manager.playQueueList(songs, initialIndex = 99, label = "Test", autoPlay = false)
        assertEquals(2, manager.queueState.index)
        manager.playQueueList(songs, initialIndex = -5, label = "Test", autoPlay = false)
        assertEquals(0, manager.queueState.index)
    }

    @Test
    fun playQueueList_updatesStateAndOrder() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        val failed = manager.playQueueList(songs, initialIndex = 1, label = "Album", autoPlay = false)
        assertEquals(0, failed)
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), manager.queueState.list.map { it.path })
        assertEquals(1, manager.queueState.index)
        assertEquals("Album", manager.queueState.label)
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), manager.originalOrder)
        assertFalse(manager.queueShuffled)
        // Native shuffle goes off for an intentional physical order.
        assertFalse(fake.shuffleEnabled)
        assertEquals(LoopMode.ALL, fake.repeatModeField)
        assertEquals(0, fake.playRequests)
    }

    @Test
    fun playQueueList_rollsBackWhenPlayerLoadFails() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        val songs = listOf(song("a.mp3"), song("b.mp3"))
        manager.setState(songs, index = 1)
        fake.failReplace = true
        val failed = manager.playQueueList(listOf(song("x.mp3")), label = "New", autoPlay = false)
        assertEquals(1, failed)
        assertEquals(listOf("a.mp3", "b.mp3"), manager.queueState.list.map { it.path })
        assertEquals(1, manager.queueState.index)
        assertEquals("Test", manager.queueState.label)
        assertEquals(listOf("a.mp3", "b.mp3"), manager.originalOrder)
    }

    // ---- removeFromQueue ----

    @Test
    fun removeFromQueue_beforeCurrentDecrementsIndex() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        manager.setState(songs, index = 2)
        manager.removeFromQueue(0)
        assertEquals(listOf("b.mp3", "c.mp3"), manager.queueState.list.map { it.path })
        assertEquals(1, manager.queueState.index)
    }

    @Test
    fun removeFromQueue_removingCurrentClampsToNewLast() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(song("a.mp3"), song("b.mp3"))
        manager.setState(songs, index = 1)
        manager.removeFromQueue(1)
        assertEquals(listOf("a.mp3"), manager.queueState.list.map { it.path })
        assertEquals(0, manager.queueState.index)
    }

    @Test
    fun removeFromQueue_lastItemClearsAllState() = runTest {
        val manager = QueueManager(FakePlayerOps())
        manager.setState(listOf(song("a.mp3")), index = 0)
        manager.removeFromQueue(0)
        assertTrue(manager.queueState.list.isEmpty())
        assertEquals(0, manager.queueState.index)
        assertEquals("", manager.queueState.label)
        assertTrue(manager.originalOrder.isEmpty())
        assertFalse(manager.queueShuffled)
    }

    // ---- reorderQueue ----

    @Test
    fun reorderQueue_movesCurrentToEndAndIndexFollowsByUri() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        manager.setState(songs, index = 0)
        manager.reorderQueue(0, 3)
        assertEquals(listOf("b.mp3", "c.mp3", "a.mp3"), manager.queueState.list.map { it.path })
        assertEquals(2, manager.queueState.index)
    }

    @Test
    fun reorderQueue_movingEarlierItemAfterCurrentAdjustsIndex() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        manager.setState(songs, index = 1)
        manager.reorderQueue(0, 3)
        assertEquals(listOf("b.mp3", "c.mp3", "a.mp3"), manager.queueState.list.map { it.path })
        assertEquals(0, manager.queueState.index)
    }

    // ---- cycleLoopMode ----

    @Test
    fun cycleLoopMode_cyclesAllOneOffAll() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        assertEquals(LoopMode.ALL, manager.loopMode)
        assertEquals(LoopMode.ONE, manager.cycleLoopMode())
        assertEquals(LoopMode.OFF, manager.cycleLoopMode())
        assertEquals(LoopMode.ALL, manager.cycleLoopMode())
        assertEquals(LoopMode.ALL, manager.loopMode)
        assertEquals(LoopMode.ALL, fake.repeatModeField)
    }

    // ---- Pure shuffle logic ----

    @Test
    fun shuffledOrderFor_keepsCurrentFirstAndPreservesSongs() {
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        val order = QueueManager.shuffledOrderFor(songs, songs[1], Random(42))
        assertEquals("b.mp3", order.first().path)
        assertEquals(setOf("a.mp3", "b.mp3", "c.mp3"), order.map { it.path }.toSet())
    }

    @Test
    fun restoredOrderFor_restoresOrderAndKeepsAdditions() {
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        val shuffled = listOf(songs[1], songs[2], songs[0])
        val (restored, idx) = QueueManager.restoredOrderFor(
            shuffled + song("d.mp3"),
            listOf("a.mp3", "b.mp3", "c.mp3"),
            "c.mp3",
        )
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3", "d.mp3"), restored.map { it.path })
        assertEquals(2, idx)
    }

    @Test
    fun restoreOriginalOrder_noOpWhenNotShuffled() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(song("a.mp3"), song("b.mp3"))
        manager.setState(songs, index = 0)
        manager.originalOrder = emptyList()
        manager.queueShuffled = false
        manager.restoreOriginalOrder()
        assertEquals(listOf("a.mp3", "b.mp3"), manager.queueState.list.map { it.path })
    }

    @Test
    fun shuffleAllQueue_noOpWithSingleSong() = runTest {
        val manager = QueueManager(FakePlayerOps())
        manager.setState(listOf(song("a.mp3")), index = 0)
        manager.shuffleAllQueue()
        assertFalse(manager.queueShuffled)
    }

    @Test
    fun shuffleAllQueue_enablesNativeShuffleAndRestoreDisablesIt() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        manager.playQueueList(songs, label = "Test", autoPlay = false)
        assertEquals(0, manager.shuffleAllQueue())
        assertTrue(manager.queueShuffled)
        assertTrue(fake.shuffleEnabled)
        assertEquals(0, manager.restoreOriginalOrder())
        assertFalse(manager.queueShuffled)
        assertFalse(fake.shuffleEnabled)
        // Native shuffle restore: no reload, order untouched.
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), manager.queueState.list.map { it.path })
    }

    // ---- skip (rapid taps) ----

    @Test
    fun multipleNextTapsAdvanceMultipleSongs() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(
            song("a.mp3"), song("b.mp3"), song("c.mp3"), song("d.mp3"), song("e.mp3"),
        )
        manager.setState(songs, index = 0)
        manager.playNext()
        assertEquals(1, manager.queueState.index)
        manager.playNext()
        manager.playNext()
        assertEquals(3, manager.queueState.index)
    }

    @Test
    fun nextAtLastWrapsToFirst() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        manager.setState(songs, index = 2)
        manager.playNext()
        assertEquals(0, manager.queueState.index)
    }

    @Test
    fun multiplePrevTapsGoBackAndWrap() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val songs = listOf(
            song("a.mp3"), song("b.mp3"), song("c.mp3"), song("d.mp3"),
        )
        manager.setState(songs, index = 0)
        manager.playPrevious()
        assertEquals(3, manager.queueState.index)
        manager.playPrevious()
        assertEquals(2, manager.queueState.index)
    }

    @Test
    fun singleSongStaysAtIndexZero() = runTest {
        val manager = QueueManager(FakePlayerOps())
        manager.setState(listOf(song("a.mp3")), index = 0)
        manager.playNext()
        assertEquals(0, manager.queueState.index)
        manager.playPrevious()
        assertEquals(0, manager.queueState.index)
    }

    @Test
    fun skipTargetFor_wrapsBothDirections() {
        assertEquals(1, QueueManager.skipTargetFor(0, 5, 1))
        assertEquals(4, QueueManager.skipTargetFor(0, 5, -1))
        assertEquals(0, QueueManager.skipTargetFor(4, 5, 1))
    }
}
