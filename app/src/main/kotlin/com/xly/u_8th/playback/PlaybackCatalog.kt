package com.xly.u_8th.playback

import com.xly.u_8th.domain.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry (uri -> Song). Senders populate it before handing
 * only URIs across the media3 session binder; the service consults it to
 * rebuild full [Song] objects (art, album, durations) without shipping big
 * payloads (TransactionTooLarge guard, as with the old EventChannel).
 */
object PlaybackCatalog {

    private val map = ConcurrentHashMap<String, Song>()

    fun putAll(songs: List<Song>) {
        for (song in songs) map[song.playUri] = song
    }

    fun put(song: Song) {
        map[song.playUri] = song
    }

    fun get(uri: String): Song? = map[uri]

    /** Registry contents (used by the service-side "Shuffle All"). */
    fun contents(): List<Song> = map.values.toList()

    fun clear() {
        map.clear()
    }
}
