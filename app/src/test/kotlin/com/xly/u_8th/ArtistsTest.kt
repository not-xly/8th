package com.xly.u_8th

import com.xly.u_8th.data.Artists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistsTest {

    @Test
    fun bracketedFeatDoesNotLeaveParenthesis() {
        assertEquals(listOf("Kanye West", "Bon Iver"), Artists.parseArtists("Kanye West (feat. Bon Iver)"))
        assertEquals(listOf("Kanye West", "Bon Iver", "Chance"), Artists.parseArtists("Kanye West (feat. Bon Iver & Chance)"))
        assertEquals(listOf("A", "B"), Artists.parseArtists("A [ft. B]"))
        assertEquals("Kanye West", Artists.primaryArtist("Kanye West (feat. Bon Iver..."))
        assertEquals(listOf("Tyler, The Creator", "Kali"), Artists.parseArtists("Tyler, The Creator (with Kali)"))
    }

    @Test
    fun `tyler the creator is a single artist`() {
        assertEquals(listOf("Tyler, The Creator"), Artists.parseArtists("Tyler, The Creator"))
        assertEquals("Tyler, The Creator", Artists.primaryArtist("Tyler, The Creator"))
    }

    @Test
    fun `comma separates distinct artists`() {
        assertEquals(listOf("Queen", "David Bowie"), Artists.parseArtists("Queen, David Bowie"))
    }

    @Test
    fun `feat separates and primary is the first`() {
        assertEquals(listOf("Eminem", "Rihanna"), Artists.parseArtists("Eminem feat. Rihanna"))
        assertEquals("Eminem", Artists.primaryArtist("Eminem feat. Rihanna"))
    }

    @Test
    fun `songHasArtist finds collaborators`() {
        val song = SongFixture.song(artist = "Queen, David Bowie")
        assertTrue(Artists.songHasArtist(song, "Queen"))
        assertTrue(Artists.songHasArtist(song, "David Bowie"))
        assertFalse(Artists.songHasArtist(song, "ABBA"))
    }

    @Test
    fun `songHasArtist respects Tyler`() {
        val song = SongFixture.song(artist = "Tyler, The Creator")
        assertTrue(Artists.songHasArtist(song, "Tyler, The Creator"))
        assertFalse(Artists.songHasArtist(song, "Tyler"))
    }

    @Test
    fun `albumArtistFor is the primary of the first track`() {
        val a = SongFixture.song(artist = "Eminem feat. Rihanna")
        val b = SongFixture.song(artist = "Eminem")
        val c = SongFixture.song(artist = "Eminem")
        assertEquals("Eminem", Artists.albumArtistFor(listOf(a, b, c)))

        val d = SongFixture.song(artist = "Rihanna feat. Eminem")
        assertEquals("Rihanna", Artists.albumArtistFor(listOf(d, b, c)))
    }

    @Test
    fun `album only appears in the first track primary artist menu`() {
        val album = listOf(
            SongFixture.song(artist = "Eminem"),
            SongFixture.song(artist = "Eminem feat. Rihanna"),
        )
        assertTrue(Artists.albumMatchesArtist(album, listOf("Eminem")))
        assertFalse(Artists.albumMatchesArtist(album, listOf("Rihanna")))
        assertFalse(Artists.albumMatchesArtist(album, listOf("Tyler, The Creator")))
    }

    @Test
    fun `joint album belongs to every credited artist`() {
        val album = listOf(
            SongFixture.song(artist = "JAY-Z & Kanye West"),
            SongFixture.song(artist = "Kanye West & JAY-Z feat. Frank Ocean"),
            SongFixture.song(artist = "JAY-Z, Kanye West"),
        )
        assertTrue(Artists.albumMatchesArtist(album, listOf("Kanye West")))
        assertTrue(Artists.albumMatchesArtist(album, listOf("JAY-Z")))
        assertFalse(Artists.albumMatchesArtist(album, listOf("Frank Ocean")))
    }

    @Test
    fun `intro by a guest does not move the album`() {
        val album = listOf(
            SongFixture.song(artist = "Guest"),
            SongFixture.song(artist = "Owner"),
            SongFixture.song(artist = "Owner feat. Guest2"),
        )
        assertTrue(Artists.albumMatchesArtist(album, listOf("Owner")))
        assertFalse(Artists.albumMatchesArtist(album, listOf("Guest")))
    }

    @Test
    fun `null and empty artists parse to empty`() {
        assertEquals(emptyList<String>(), Artists.parseArtists(null))
        assertEquals(emptyList<String>(), Artists.parseArtists("   "))
        assertNull(Artists.primaryArtist(null))
    }

    @Test
    fun `normalize lowercases and trims`() {
        assertEquals("queen", Artists.normalize("  QUEEN "))
    }
}

object SongFixture {
    fun song(
        path: String = "content://media/1",
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        trackNumber: Int? = null,
        durationMs: Long? = null,
    ) = com.xly.u_8th.domain.Song(
        path = path,
        contentUri = path,
        title = title,
        artist = artist,
        album = album,
        trackNumber = trackNumber,
        durationMs = durationMs,
    )
}
