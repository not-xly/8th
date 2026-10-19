package com.xly.u_8th

import com.xly.u_8th.domain.Playlist
import com.xly.u_8th.domain.PlaylistCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistCodecTest {

    @Test
    fun `round trip keeps order, names and paths`() {
        val lists = listOf(
            Playlist("a", "Para correr", listOf("/m/1.mp3", "content://media/2", "/m/3 #x?.flac")),
            Playlist("b", "Vacía"),
        )
        assertEquals(lists, PlaylistCodec.decode(PlaylistCodec.encode(lists)))
    }

    @Test
    fun `empty or null decodes to empty`() {
        assertEquals(emptyList<Playlist>(), PlaylistCodec.decode(null))
        assertEquals(emptyList<Playlist>(), PlaylistCodec.decode(""))
    }

    @Test
    fun `names are cleaned of control chars and newlines`() {
        assertEquals("Mi lista", PlaylistCodec.cleanName("  Mi\nlista\u001E "))
        val decoded = PlaylistCodec.decode(PlaylistCodec.encode(listOf(Playlist("x", "A\u001FB", listOf("/p")))))
        assertEquals(listOf(Playlist("x", "A B", listOf("/p"))), decoded)
    }
}
