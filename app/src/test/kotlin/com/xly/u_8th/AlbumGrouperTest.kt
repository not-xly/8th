package com.xly.u_8th

import com.xly.u_8th.data.AlbumGrouper
import com.xly.u_8th.domain.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumGrouperTest {

    @Test
    fun `groups by album and sorts by track`() {
        val songs = listOf(
            song("c.mp3", album = "Album B", track = 2, title = "B"),
            song("b.mp3", album = "Album B", track = 1, title = "A"),
            song("a.mp3", album = "Album A", track = 1, title = "A"),
            song("d.mp3", album = null, title = "Sin album"),
        )
        val grouped = AlbumGrouper.groupByAlbum(songs)
        assertEquals(listOf("Album A", "Album B", "Desconocido"), grouped.keys.toList())
        assertEquals(listOf(1, 2), grouped["Album B"]!!.map { it.trackNumber })
    }

    @Test
    fun `sorts alphabetically with Desconocido last`() {
        val songs = listOf(
            song("1.mp3", album = "Zebra"),
            song("2.mp3", album = "Alpha"),
            song("3.mp3", album = null),
        )
        val grouped = AlbumGrouper.groupByAlbum(songs)
        assertEquals(listOf("Alpha", "Zebra", "Desconocido"), grouped.keys.toList())
    }

    @Test
    fun `title breaks track ties`() {
        val songs = listOf(
            song("z.mp3", album = "X", track = 1, title = "Zeta"),
            song("a.mp3", album = "X", track = 1, title = "Alpha"),
        )
        val grouped = AlbumGrouper.groupByAlbum(songs)
        assertEquals("Alpha", grouped["X"]!![0].title)
    }

    @Test
    fun `greatest hits of two artists do not merge`() {
        val songs = listOf(
            song("q.mp3", album = "Greatest Hits", artist = "Queen"),
            song("ab.mp3", album = "Greatest Hits", artist = "ABBA"),
        )
        val grouped = AlbumGrouper.groupByAlbum(songs)
        assertEquals(2, grouped.size)
    }

    @Test
    fun `feats of the same album do not fragment it`() {
        val songs = listOf(
            song("1.mp3", album = "Recovery", artist = "Eminem"),
            song("2.mp3", album = "Recovery", artist = "Eminem feat. Rihanna"),
            song("3.mp3", album = "Recovery", artist = "Eminem"),
        )
        val grouped = AlbumGrouper.groupByAlbum(songs)
        assertEquals(1, grouped.size)
        assertEquals(3, grouped["Recovery"]!!.size)
    }

    @Test
    fun `same name different artists splits with artist suffix`() {
        val songs = listOf(
            song("q.mp3", album = "Hits", artist = "Queen"),
            song("ab.mp3", album = "Hits", artist = "ABBA"),
        )
        val grouped = AlbumGrouper.groupByAlbum(songs)
        assertEquals(2, grouped.size)
        assertEquals(1, grouped.values.first().size)
    }

    @Test
    fun `sortedAlbumKeys respects order and appends the rest`() {
        val grouped = linkedMapOf(
            "A" to emptyList<Song>(),
            "B" to emptyList(),
            "C" to emptyList(),
        )
        assertEquals(listOf("C", "A", "B"), AlbumGrouper.sortedAlbumKeys(grouped, listOf("C", "A")))
    }

    @Test
    fun `sortedAlbumKeys ignores unknown entries in order`() {
        val grouped = linkedMapOf(
            "A" to emptyList<Song>(),
            "B" to emptyList(),
        )
        assertEquals(listOf("A", "B"), AlbumGrouper.sortedAlbumKeys(grouped, listOf("X", "A")))
    }

    @Test
    fun `resolveTrack uses trackNumber then title digits then 999`() {
        assertEquals(5, AlbumGrouper.resolveTrack(song("a.mp3", track = 5, title = "10 Otro")))
        assertEquals(3, AlbumGrouper.resolveTrack(song("a.mp3", title = "03 - Intro")))
        assertEquals(999, AlbumGrouper.resolveTrack(song("a.mp3", title = "Intro")))
        assertEquals(7, AlbumGrouper.resolveTrack(song("/music/07_track.mp3")))
    }

    private fun song(
        path: String,
        album: String? = null,
        artist: String? = null,
        track: Int? = null,
        title: String? = null,
    ): Song = Song(
        path = path,
        contentUri = path,
        title = title,
        artist = artist,
        album = album,
        trackNumber = track,
    )
}
