package com.xly.u_8th.playback

import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

class QueueManager(private val player: PlayerOps, private val random: Random = Random.Default) {

    class QueueState(var list: MutableList<Song>, var index: Int, var label: String)

    val queueState = QueueState(mutableListOf(), 0, "")
    var originalOrder: List<String> = emptyList()
    var queueShuffled = false
    var loopMode = LoopMode.ALL
    var queueRevision = 0
        private set

    private var entryIds = mutableListOf<Long>()
    private var originalIds = emptyList<Long>()
    private var nextId = 0L
    private val failedEntries = mutableSetOf<Long>()
    private var recoveryAttempts = 0

    val currentSong: Song?
        get() = queueState.list.getOrNull(queueState.index)

    private fun ensureEntries() {
        if (entryIds.size == queueState.list.size) return
        entryIds = queueState.list.map { nextId++ }.toMutableList()
        originalIds = entryIds.toList()
    }

    private fun touchQueue() {
        queueRevision++
        val songsById = entryIds.zip(queueState.list).toMap()
        originalOrder = originalIds.mapNotNull { songsById[it]?.playUri }
    }

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
        val selected = initialIndex.coerceIn(songs.indices)
        val previous = currentSongBefore ?: currentSong
        val position = if (resumeIfSameSong && previous?.playUri == songs[selected].playUri) {
            player.positionMs
        } else {
            0L
        }
        val order = if (shuffled) {
            listOf(selected) + songs.indices.filter { it != selected }.shuffled(random)
        } else {
            songs.indices.toList()
        }
        val ids = songs.map { nextId++ }
        val state = PlaybackSession(
            songs = order.map { songs[it] },
            entryIds = order.map { ids[it] },
            originalIds = ids,
            index = if (shuffled) 0 else selected,
            positionMs = position,
            label = label,
            shuffled = shuffled,
            loopMode = loopMode,
        )
        return if (loadSession(state, autoPlay)) 0 else songs.size
    }

    private fun loadSession(state: PlaybackSession, autoPlay: Boolean): Boolean {
        val before = snapshot()
        applyState(state)
        if (!autoPlay) player.pause()
        val replaced = try {
            if (state.songs.isEmpty()) {
                player.pause()
                player.clearItems()
                true
            } else {
                player.replaceItems(state.songs, state.index, state.positionMs)
            }
        } catch (e: CancellationException) {
            applyState(before)
            throw e
        } catch (_: Exception) {
            false
        }
        if (!replaced) {
            applyState(before)
            return false
        }
        resetErrorRecovery()
        player.setShuffleEnabled(false)
        player.setRepeatMode(loopMode)
        if (autoPlay && state.songs.isNotEmpty()) player.play() else player.pause()
        return true
    }

    private fun applyState(state: PlaybackSession) {
        queueState.list = state.songs.toMutableList()
        queueState.index = state.index
        queueState.label = state.label
        entryIds = state.entryIds.toMutableList()
        originalIds = state.originalIds.toList()
        nextId = maxOf(nextId, (entryIds.maxOrNull() ?: -1L) + 1L)
        queueShuffled = state.shuffled
        loopMode = state.loopMode
        touchQueue()
    }

    fun snapshot(): PlaybackSession {
        ensureEntries()
        return PlaybackSession(
            songs = queueState.list.toList(),
            entryIds = entryIds.toList(),
            originalIds = originalIds.toList(),
            index = queueState.index,
            positionMs = player.positionMs.coerceAtLeast(0L),
            label = queueState.label,
            shuffled = queueShuffled,
            loopMode = loopMode,
        )
    }

    fun restoreSession(state: PlaybackSession): Boolean = loadSession(state, autoPlay = false)

    fun playNext() = skip(1)

    fun playPrevious() = skip(-1)

    private fun skip(direction: Int) {
        val n = queueState.list.size
        if (n == 0) return
        val target = skipTargetFor(queueState.index, n, direction)
        if (loopMode == LoopMode.OFF && queueState.index + direction !in 0 until n) return
        seekToIndex(target)
    }

    suspend fun addToQueueNext(song: Song): Boolean =
        add(song, (queueState.index + 1).coerceIn(0, queueState.list.size))

    suspend fun addToQueueEnd(song: Song): Boolean = add(song, queueState.list.size)

    private suspend fun add(song: Song, index: Int): Boolean {
        if (queueState.list.isEmpty()) {
            return playQueueList(listOf(song), label = "Cola personalizada") == 0
        }
        ensureEntries()
        val added = try {
            player.addItem(song, index)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!added) return false
        val id = nextId++
        queueState.list.add(index, song)
        entryIds.add(index, id)
        originalIds = if (queueShuffled) originalIds + id else entryIds.toList()
        touchQueue()
        return true
    }

    fun removeFromQueue(index: Int) {
        if (index !in queueState.list.indices) return
        ensureEntries()
        val currentIndex = queueState.index
        val id = entryIds.removeAt(index)
        queueState.list.removeAt(index)
        originalIds = originalIds.filter { it != id }
        queueState.index = when {
            queueState.list.isEmpty() -> 0
            index < currentIndex -> currentIndex - 1
            else -> currentIndex.coerceAtMost(queueState.list.lastIndex)
        }
        if (queueState.list.isEmpty()) {
            queueState.label = ""
            queueShuffled = false
        }
        touchQueue()
        if (player.itemCount > 0) {
            if (queueState.list.isEmpty()) {
                player.pause()
                player.clearItems()
            } else {
                player.removeItemAt(index)
            }
        }
    }

    fun reorderQueue(oldIndex: Int, newIndex: Int) {
        if (oldIndex !in queueState.list.indices || newIndex !in 0..queueState.list.size) return
        val target = if (oldIndex < newIndex) newIndex - 1 else newIndex
        if (oldIndex == target) return
        ensureEntries()
        val currentId = entryIds.getOrNull(queueState.index)
        val item = queueState.list.removeAt(oldIndex)
        queueState.list.add(target, item)
        val id = entryIds.removeAt(oldIndex)
        entryIds.add(target, id)
        queueState.index = entryIds.indexOf(currentId).coerceAtLeast(0)
        if (!queueShuffled) originalIds = entryIds.toList()
        touchQueue()
        if (player.itemCount > 0) player.moveItem(oldIndex, target)
    }

    fun shuffleAllQueue(): Int {
        if (queueShuffled || queueState.list.size <= 1) return 0
        val state = snapshot()
        val order = listOf(state.index) + state.songs.indices.filter { it != state.index }.shuffled(random)
        val shuffled = state.copy(
            songs = order.map { state.songs[it] },
            entryIds = order.map { state.entryIds[it] },
            originalIds = state.entryIds,
            index = 0,
            shuffled = true,
        )
        return if (reorderSession(shuffled)) 0 else state.songs.size
    }

    suspend fun restoreOriginalOrder(): Int {
        if (!queueShuffled || queueState.list.isEmpty()) return 0
        val state = snapshot()
        val currentId = state.entryIds[state.index]
        val byId = state.entryIds.zip(state.songs).toMap()
        val originalSet = state.originalIds.toHashSet()
        val ids = state.originalIds.filter { it in byId } + state.entryIds.filter { it !in originalSet }
        val restored = state.copy(
            songs = ids.map { byId.getValue(it) },
            entryIds = ids,
            originalIds = ids,
            index = ids.indexOf(currentId),
            shuffled = false,
        )
        return if (reorderSession(restored)) 0 else state.songs.size
    }

    /**
     * Mismo contenido en otro orden (aleatorio / orden original): cambia lo
     * que hay alrededor de la canción actual sin recargarla, así no se corta.
     * Si el reproductor no cuadra con la cola, recarga la sesión entera.
     */
    private fun reorderSession(state: PlaybackSession): Boolean {
        val inSync = player.itemCount == queueState.list.size && player.currentIndex == queueState.index
        if (inSync) {
            val before = snapshot()
            applyState(state)
            if (runCatching { player.replaceAroundCurrent(state.songs, state.index) }.getOrDefault(false)) return true
            applyState(before)
        }
        return loadSession(state, player.playing)
    }

    suspend fun toggleShuffle(enable: Boolean) {
        if (enable) shuffleAllQueue() else restoreOriginalOrder()
    }

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
        loopMode = LoopMode.ALL
        player.setRepeatMode(loopMode)
    }

    fun seekToIndex(index: Int) {
        if (index !in queueState.list.indices) return
        val previous = queueState.index
        queueState.index = index
        if (player.itemCount == 0) return
        val ok = runCatching { player.seekToIndex(index) }.getOrDefault(false)
        if (!ok) queueState.index = previous
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun onMediaItemTransition(index: Int) {
        if (index in queueState.list.indices) queueState.index = index
    }

    fun onPlaybackEnded() = player.stopForEnd()

    fun resetErrorRecovery() {
        failedEntries.clear()
        recoveryAttempts = 0
    }

    fun onPlaybackError(index: Int, continuePlaying: Boolean): Boolean {
        ensureEntries()
        if (index !in entryIds.indices) {
            player.pause()
            return false
        }
        failedEntries.add(entryIds[index])
        val size = entryIds.size
        val maxAttempts = minOf(size, MAX_RECOVERY_ATTEMPTS)
        while (recoveryAttempts < maxAttempts) {
            // Con LoopMode.OFF la cola termina tras la última pista, así que
            // solo se recorren las posiciones por delante de `index`; con
            // repeat activo se da la vuelta completa. El filtro comprobaba
            // `it < size` sobre un índice ya reducido, o sea que nunca
            // descartaba nada.
            val limit = if (loopMode == LoopMode.OFF) size - index else size
            val target = (1 until limit).firstOrNull { entryIds[(index + it) % size] !in failedEntries }
                ?.let { (index + it) % size }
                ?: break
            recoveryAttempts++
            queueState.index = target
            val recovered = try {
                if (player.seekToIndex(target)) {
                    player.prepareAfterError()
                    if (continuePlaying) player.play() else player.pause()
                    true
                } else false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (recovered) return true
            failedEntries.add(entryIds[target])
        }
        queueState.index = player.currentIndex.takeIf { it in entryIds.indices } ?: index
        player.pause()
        return false
    }

    companion object {
        const val MAX_RECOVERY_ATTEMPTS = 10

        fun skipTargetFor(index: Int, n: Int, direction: Int): Int {
            if (n <= 0) return index
            return ((index + direction) % n + n) % n
        }
    }
}
