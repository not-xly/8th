package com.xly.u_8th

import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlaybackSession
import com.xly.u_8th.playback.PlaybackSessionCodec
import com.xly.u_8th.playback.RestoreGate
import com.xly.u_8th.playback.SessionSaveScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionTest {
    private fun session() = PlaybackSession(
        songs = listOf(
            Song("b", contentUri = "content://audio/2", title = "Título", artist = "Artist", album = "Album", durationMs = 9000),
            Song("a"),
            Song("a"),
        ),
        entryIds = listOf(2L, 0L, 1L),
        originalIds = listOf(0L, 1L, 2L),
        index = 2,
        positionMs = 1234L,
        label = "Guardado",
        shuffled = true,
        loopMode = LoopMode.ONE,
    )

    @Test
    fun codecRoundTripPreservesDuplicatesEntryOrderAndMetadata() {
        val saved = session()
        val bytes = ByteArrayOutputStream().also { PlaybackSessionCodec.write(saved, it) }.toByteArray()
        val loaded = PlaybackSessionCodec.read(ByteArrayInputStream(bytes))
        assertEquals(saved, loaded)
        assertEquals("Título", loaded.songs[0].title)
        assertEquals("content://audio/2", loaded.songs[0].contentUri)
        assertEquals("Artist", loaded.songs[0].artist)
        assertEquals("Album", loaded.songs[0].album)
        assertEquals(9000L, loaded.songs[0].durationMs)
    }

    @Test
    fun emptyQueueIsPersistedInsteadOfResurrectingPreviousSession() {
        val empty = session().retaining { false }
        val bytes = ByteArrayOutputStream().also { PlaybackSessionCodec.write(empty, it) }.toByteArray()
        val restored = PlaybackSessionCodec.read(ByteArrayInputStream(bytes))
        assertTrue(restored.songs.isEmpty())
        assertEquals(0, restored.index)
        assertEquals(0L, restored.positionMs)
        assertFalse(restored.shuffled)
    }

    @Test
    fun inaccessibleEntriesRemapCurrentDuplicateAndKeepPosition() {
        val restored = session().retaining { it.path != "b" }
        assertEquals(listOf(0L, 1L), restored.entryIds)
        assertEquals(listOf(0L, 1L), restored.originalIds)
        assertEquals(1, restored.index)
        assertEquals(1234L, restored.positionMs)
    }

    @Test
    fun missingCurrentSelectsNextReadableEntryAtZero() {
        val restored = session().copy(index = 0).retaining { it.path != "b" }
        assertEquals(0, restored.index)
        assertEquals(0L, restored.positionMs)
        assertEquals(2, restored.songs.size)
    }

    @Test
    fun corruptAndTruncatedSessionsAreRejected() {
        val bytes = ByteArrayOutputStream().also { PlaybackSessionCodec.write(session(), it) }.toByteArray()
        assertTrue(runCatching { PlaybackSessionCodec.read(ByteArrayInputStream(bytes.copyOf(8))) }.isFailure)
        bytes[3] = 99
        assertTrue(runCatching { PlaybackSessionCodec.read(ByteArrayInputStream(bytes)) }.isFailure)
    }

    @Test
    fun commandsInvalidatePendingRestoreEvenWhenLoadIgnoresCancellation() {
        val gate = RestoreGate()
        val revision = gate.revision
        assertTrue(gate.accepts(revision))
        gate.command()
        assertFalse(gate.accepts(revision))
    }

    @Test
    fun savesAreDebouncedAndLatestSessionSurvivesServiceRecreation() = runTest {
        val writes = mutableListOf<PlaybackSession>()
        val scheduler = SessionSaveScheduler(this) { writes.add(it) }
        val first = session()
        scheduler.schedule { first }
        runCurrent()
        advanceTimeBy(500)
        val newest = first.copy(positionMs = 4000L)
        var snapshotsTaken = 0
        scheduler.schedule { snapshotsTaken++; newest }
        // El snapshot es perezoso: no se calcula hasta que el debounce vence,
        // así que una ráfaga de eventos del player no instantanea la cola.
        runCurrent()
        assertEquals(0, snapshotsTaken)
        advanceTimeBy(749)
        assertEquals(0, snapshotsTaken)
        assertTrue(writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, snapshotsTaken)
        assertEquals(newest, scheduler.latest)
        assertEquals(listOf(newest), writes)
        scheduler.schedule { first }
        scheduler.schedule(immediate = true) { newest.copy(positionMs = 5000L) }
        runCurrent()
        assertEquals(2, writes.size)
        assertEquals(5000L, writes.last().positionMs)
    }

    @Test
    fun snapshotRunsInPlayerContext() = runTest {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "player-thread") }
        val playerDispatcher = executor.asCoroutineDispatcher()
        try {
            var snapshotThread: String? = null
            val written = kotlinx.coroutines.CompletableDeferred<PlaybackSession>()
            val scheduler = SessionSaveScheduler(this, snapshotContext = playerDispatcher) { written.complete(it) }
            scheduler.schedule(immediate = true) {
                snapshotThread = Thread.currentThread().name
                session()
            }
            written.await()
            assertTrue(snapshotThread.orEmpty().startsWith("player-thread"))
        } finally {
            playerDispatcher.close()
        }
    }
}
