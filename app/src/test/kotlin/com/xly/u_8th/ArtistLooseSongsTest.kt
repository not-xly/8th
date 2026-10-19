package com.xly.u_8th

import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistLooseSongsTest {

    private fun song(path: String, artist: String, album: String) = Song(path = path, artist = artist, album = album)

    @Test
    fun `songs inside the artist albums are excluded`() {
        val grouped = linkedMapOf(
            "Disco" to listOf(song("a1", "Queen", "Disco"), song("a2", "Queen", "Disco")),
            "Sueltas" to listOf(song("s1", "Queen", "Single"), song("o1", "Otro", "Single"), song("o2", "Otro", "Single")),
        )
        val loose = Artists.looseSongsOf("Queen", grouped)
        assertEquals(listOf("s1"), loose.map { it.path })
    }

    @Test
    fun `collaboration songs are counted`() {
        val grouped = linkedMapOf(
            "Disco" to listOf(song("a1", "Queen", "Disco"), song("a2", "Queen", "Disco")),
            "Ajeno" to listOf(
                song("x1", "David Bowie feat. Queen", "Ajeno"),
                song("x2", "David Bowie", "Ajeno"),
                song("x3", "David Bowie", "Ajeno"),
            ),
        )
        val loose = Artists.looseSongsOf("Queen", grouped)
        assertEquals(listOf("x1"), loose.map { it.path })
    }

    @Test
    fun `empty when every song is in an album`() {
        val grouped = linkedMapOf(
            "Disco" to listOf(song("a1", "Queen", "Disco"), song("a2", "Queen & David Bowie", "Disco")),
            "Otro" to listOf(song("o1", "Otro", "Otro")),
        )
        assertTrue(Artists.looseSongsOf("Queen", grouped).isEmpty())
    }
}
