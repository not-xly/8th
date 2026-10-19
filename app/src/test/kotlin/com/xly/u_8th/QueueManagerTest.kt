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
        var failSeek = false
        var prepareRequests = 0
        var playRequests = 0
        var stoppedForEnd = false
        var repeatModeField = LoopMode.ALL

        var replaceCalls = 0

        override fun replaceAroundCurrent(songs: List<Song>, currentAt: Int): Boolean {
            if (failReplace) return false
            items.clear()
            items.addAll(songs)
            currentIndex = currentAt
            return true
        }

        override fun replaceItems(songs: List<Song>, startIndex: Int, startPositionMs: Long): Boolean {
            replaceCalls++
            if (failReplace) return false
            items.clear()
            items.addAll(songs)
            currentIndex = startIndex
            positionMsField = startPositionMs
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
            if (failSeek) return false
            currentIndex = index
            positionMsField = 0L
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

        override fun prepareAfterError() {
            prepareRequests++
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
    fun shuffleAllQueue_physicallyShufflesAndRestoresOriginalOrder() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))
        manager.playQueueList(songs, label = "Test", autoPlay = false)
        assertEquals(0, manager.shuffleAllQueue())
        assertTrue(manager.queueShuffled)
        assertFalse(fake.shuffleEnabled)
        assertEquals(0, manager.restoreOriginalOrder())
        assertFalse(manager.queueShuffled)
        assertFalse(fake.shuffleEnabled)
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
    fun firstShuffledQueueRetainsOriginalOrderAndSelectedDuplicate() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake, Random(42))
        val songs = listOf(song("a"), song("b"), song("a"), song("c"))
        manager.playQueueList(songs, 2, "Original", autoPlay = false, shuffled = true)
        assertEquals(0, manager.queueState.index)
        assertEquals(songs.map { it.path }, manager.originalOrder)
        assertEquals(4, manager.queueState.list.size)
        assertFalse(fake.shuffleEnabled)
        fake.positionMsField = 1234L
        manager.restoreOriginalOrder()
        assertEquals(songs, manager.queueState.list)
        assertEquals(2, manager.queueState.index)
        assertEquals(1234L, fake.positionMs)
    }

    @Test
    fun replacingShuffledQueueDoesNotReusePreviousOriginalOrder() = runTest {
        val manager = QueueManager(FakePlayerOps(), Random(42))
        manager.playQueueList(listOf(song("x"), song("y")), label = "Old", shuffled = true)
        val songs = listOf(song("a"), song("b"), song("c"))
        manager.playQueueList(songs, 1, "New", shuffled = true)
        manager.restoreOriginalOrder()
        assertEquals(songs, manager.queueState.list)
        assertEquals(1, manager.queueState.index)
    }

    @Test
    fun reorderFollowsEntryNotFirstMatchingUri() = runTest {
        for (loaded in listOf(false, true)) {
            val fake = FakePlayerOps()
            val manager = QueueManager(fake)
            val songs = listOf(song("a"), song("b"), song("a"), song("c"))
            if (loaded) manager.playQueueList(songs, 2, "Test") else manager.setState(songs, 2)
            manager.reorderQueue(0, 4)
            assertEquals(1, manager.queueState.index)
            manager.reorderQueue(1, 4)
            assertEquals(3, manager.queueState.index)
        }
    }

    @Test
    fun shuffledMutationsPreserveDuplicateIdentityAndAdditions() = runTest {
        val manager = QueueManager(FakePlayerOps(), Random(42))
        manager.playQueueList(listOf(song("a"), song("b"), song("a")), 2, "Test", shuffled = true)
        manager.addToQueueEnd(song("d"))
        manager.reorderQueue(0, 4)
        manager.restoreOriginalOrder()
        assertEquals(listOf("a", "b", "a", "d"), manager.queueState.list.map { it.path })
        assertEquals(2, manager.queueState.index)
        manager.shuffleAllQueue()
        manager.removeFromQueue(0)
        manager.restoreOriginalOrder()
        assertEquals(listOf("a", "b", "d"), manager.queueState.list.map { it.path })
    }

    @Test
    fun sessionRestoresPausedWithExactQueueIndexPositionAndModes() = runTest {
        val first = QueueManager(FakePlayerOps(), Random(42))
        first.playQueueList(listOf(song("a"), song("b"), song("a")), 2, "Saved", shuffled = true)
        first.cycleLoopMode()
        val saved = first.snapshot().copy(positionMs = 8765L)
        val fake = FakePlayerOps().apply { playing = true }
        val restored = QueueManager(fake)
        assertTrue(restored.restoreSession(saved))
        assertFalse(fake.playing)
        assertEquals(0, fake.playRequests)
        assertEquals(saved.songs, fake.items)
        assertEquals(saved.index, fake.currentIndex)
        assertEquals(8765L, fake.positionMs)
        assertEquals(LoopMode.ONE, fake.repeatModeField)
        assertTrue(restored.queueShuffled)
        restored.restoreOriginalOrder()
        assertEquals(2, restored.queueState.index)
        assertFalse(fake.playing)
    }

    @Test
    fun autoPlayFalsePausesPreviouslyPlayingPlayer() = runTest {
        val fake = FakePlayerOps().apply { playing = true }
        QueueManager(fake).playQueueList(listOf(song("a")), label = "Paused", autoPlay = false)
        assertFalse(fake.playing)
    }

    @Test
    fun errorRecoveryStopsAfterAllEntriesFailEvenWithRepeatOne() = runTest {
        for (mode in LoopMode.entries) {
            val fake = FakePlayerOps()
            val manager = QueueManager(fake)
            manager.loopMode = mode
            manager.playQueueList(listOf(song("a"), song("b"), song("a")), label = "Test")
            assertTrue(manager.onPlaybackError(0, true))
            assertTrue(manager.onPlaybackError(1, true))
            assertFalse(manager.onPlaybackError(2, true))
            assertFalse(fake.playing)
            assertEquals(listOf(1, 2), fake.seeksToIndex)
            assertEquals(2, fake.prepareRequests)
        }
    }

    @Test
    fun errorRecoveryWithLoopOffDoesNotWrapBackwards() = runTest {
        // Con repeat desactivado la cola acaba en la última pista: al fallar
        // la 3 de 4 no debe reintentar desde la 1ª (el filtro anterior
        // comparaba un índice ya reducido, así que nunca descartaba nada).
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        manager.loopMode = LoopMode.OFF
        manager.playQueueList(listOf(song("a"), song("b"), song("c"), song("d")), label = "Test")
        manager.queueState.index = 2
        assertTrue(manager.onPlaybackError(2, true))
        assertEquals(listOf(3), fake.seeksToIndex)
        // Ya en la última pista no queda nada por delante: se detiene.
        assertFalse(manager.onPlaybackError(3, true))
        assertEquals(listOf(3), fake.seeksToIndex)
        assertFalse(fake.playing)
    }

    @Test
    fun errorRecoveryHasHardCapAndNewCommandCanRetry() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        manager.playQueueList((0..100).map { song("$it") }, label = "Test")
        repeat(QueueManager.MAX_RECOVERY_ATTEMPTS) { assertTrue(manager.onPlaybackError(it, true)) }
        assertFalse(manager.onPlaybackError(QueueManager.MAX_RECOVERY_ATTEMPTS, true))
        assertEquals(QueueManager.MAX_RECOVERY_ATTEMPTS, fake.seeksToIndex.size)
        manager.resetErrorRecovery()
        assertTrue(manager.onPlaybackError(fake.currentIndex, false))
        assertFalse(fake.playing)
    }

    @Test
    fun failedSynchronousSeeksAreBoundedAndSingleInvalidFileStops() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        manager.playQueueList(listOf(song("a")), label = "Test")
        assertFalse(manager.onPlaybackError(0, true))
        assertTrue(fake.seeksToIndex.isEmpty())
        assertFalse(fake.playing)
        manager.playQueueList((0..100).map { song("$it") }, label = "Test")
        fake.failSeek = true
        assertFalse(manager.onPlaybackError(0, true))
        assertEquals(QueueManager.MAX_RECOVERY_ATTEMPTS, fake.seeksToIndex.size)
        assertFalse(fake.playing)
    }

    @Test
    fun failedShuffleRollsBackFlagOrderAndIndex() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        manager.playQueueList(listOf(song("a"), song("b")), 1, "Test")
        fake.failReplace = true
        assertEquals(2, manager.shuffleAllQueue())
        assertFalse(manager.queueShuffled)
        assertEquals(listOf("a", "b"), manager.originalOrder)
        assertEquals(1, manager.queueState.index)
    }

    @Test
    fun shuffleAndRestoreReorderWithoutReloadingCurrent() = runTest {
        val fake = FakePlayerOps()
        val manager = QueueManager(fake)
        manager.playQueueList(listOf(song("a"), song("b"), song("c"), song("d")), 1, "Test")
        val reloads = fake.replaceCalls
        assertEquals(0, manager.shuffleAllQueue())
        assertEquals(reloads, fake.replaceCalls)
        assertEquals("b", fake.items.first().path)
        assertEquals(0, fake.currentIndex)
        assertEquals(manager.queueState.list, fake.items)
        manager.toggleShuffle(false)
        assertEquals(reloads, fake.replaceCalls)
        assertEquals(listOf("a", "b", "c", "d"), fake.items.map { it.path })
        assertEquals(1, fake.currentIndex)
        assertEquals(1, manager.queueState.index)
    }

    @Test
    fun autoSnapshotsClearEmptyQueueCacheItemsAndIncludeRevision() = runTest {
        val manager = QueueManager(FakePlayerOps())
        val cache = com.xly.u_8th.playback.AutoSnapshotCache()
        manager.playQueueList(listOf(song("aa"), song("bb"), song("cc")), label = "Test", autoPlay = false)
        val first = cache.snapshot(manager, 0, 10000, false)
        val seek = cache.snapshot(manager, 1500, 10000, false)
        org.junit.Assert.assertSame(first.queue, seek.queue)
        assertEquals(1500L, seek.positionMs)
        val revisionBefore = manager.queueRevision
        manager.reorderQueue(1, 3)
        val reordered = cache.snapshot(manager, 1500, 10000, false)
        assertTrue(revisionBefore != manager.queueRevision)
        assertEquals(listOf("aa", "cc", "bb"), reordered.queue.map { it.uri })
        repeat(3) { manager.removeFromQueue(0) }
        val empty = cache.snapshot(manager, 1500, 10000, true)
        assertEquals("", empty.uri)
        assertTrue(empty.queue.isEmpty())
        assertFalse(empty.playing)
        assertEquals(0L, empty.positionMs)
    }

    @Test
    fun skipTargetFor_wrapsBothDirections() {
        assertEquals(1, QueueManager.skipTargetFor(0, 5, 1))
        assertEquals(4, QueueManager.skipTargetFor(0, 5, -1))
        assertEquals(0, QueueManager.skipTargetFor(4, 5, 1))
    }
}
