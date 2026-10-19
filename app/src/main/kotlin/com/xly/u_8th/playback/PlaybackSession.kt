package com.xly.u_8th.playback

import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

data class PlaybackSession(
    val songs: List<Song>,
    val entryIds: List<Long>,
    val originalIds: List<Long>,
    val index: Int,
    val positionMs: Long,
    val label: String,
    val shuffled: Boolean,
    val loopMode: LoopMode,
) {
    fun retaining(canRead: (Song) -> Boolean): PlaybackSession = retainingIndexed { canRead(songs[it]) }

    /**
     * Como [retaining], pero el predicado recibe el índice: permite que el
     * llamante reutilice un resultado ya calculado por lotes (por ejemplo, un
     * sondeo de legibilidad en paralelo).
     */
    fun retainingIndexed(canRead: (Int) -> Boolean): PlaybackSession {
        val kept = songs.indices.filter { canRead(it) }
        val selected = kept.indexOf(index)
        val next = kept.indexOfFirst { it > index }.let { if (it < 0) kept.lastIndex else it }
        val ids = kept.map { entryIds[it] }
        val idSet = ids.toHashSet()
        return copy(
            songs = kept.map { songs[it] },
            entryIds = ids,
            originalIds = originalIds.filter { it in idSet },
            index = if (selected >= 0) selected else next.coerceAtLeast(0),
            positionMs = if (selected >= 0) positionMs else 0L,
            shuffled = shuffled && kept.isNotEmpty(),
            label = if (kept.isEmpty()) "" else label,
        )
    }
}

object PlaybackSessionCodec {
    private const val VERSION = 1
    private const val MAX_ITEMS = 100_000
    private const val MAX_STRING_BYTES = 1_048_576

    fun write(session: PlaybackSession, output: OutputStream) {
        val data = DataOutputStream(output)
        data.writeInt(VERSION)
        data.writeInt(session.songs.size)
        session.songs.forEachIndexed { index, song ->
            data.writeLong(session.entryIds[index])
            data.text(song.path)
            data.text(song.contentUri)
            data.text(song.filePath)
            data.text(song.title)
            data.text(song.artist)
            data.text(song.album)
            data.writeInt(song.trackNumber ?: -1)
            data.writeLong(song.durationMs ?: -1L)
            data.text(song.albumArtUri)
        }
        data.writeInt(session.originalIds.size)
        session.originalIds.forEach { data.writeLong(it) }
        data.writeInt(session.index)
        data.writeLong(session.positionMs)
        data.text(session.label)
        data.writeBoolean(session.shuffled)
        data.writeInt(session.loopMode.wire)
        data.flush()
    }

    fun read(input: InputStream): PlaybackSession {
        val data = DataInputStream(input)
        require(data.readInt() == VERSION)
        val size = data.readInt().also { require(it in 0..MAX_ITEMS) }
        val ids = ArrayList<Long>(size)
        val songs = List(size) {
            ids.add(data.readLong().also { require(it in 0 until Long.MAX_VALUE) })
            Song(
                path = requireNotNull(data.text()),
                contentUri = data.text(),
                filePath = data.text(),
                title = data.text(),
                artist = data.text(),
                album = data.text(),
                trackNumber = data.readInt().takeIf { it >= 0 },
                durationMs = data.readLong().takeIf { it >= 0 },
                albumArtUri = data.text(),
            )
        }
        require(ids.toSet().size == size)
        val orderSize = data.readInt().also { require(it == size) }
        val order = List(orderSize) { data.readLong() }
        require(order.toSet() == ids.toSet())
        val index = data.readInt().also { require(if (size == 0) it == 0 else it in 0 until size) }
        return PlaybackSession(
            songs = songs,
            entryIds = ids,
            originalIds = order,
            index = index,
            positionMs = data.readLong().coerceAtLeast(0L),
            label = data.text().orEmpty(),
            shuffled = data.readBoolean(),
            loopMode = LoopMode.fromWire(data.readInt()),
        )
    }

    private fun DataOutputStream.text(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES)
            writeInt(bytes.size)
            write(bytes)
        }
    }

    private fun DataInputStream.text(): String? {
        val length = readInt()
        if (length == -1) return null
        require(length in 0..MAX_STRING_BYTES)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
