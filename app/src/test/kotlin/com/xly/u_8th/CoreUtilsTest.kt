package com.xly.u_8th

import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.AppStrings
import com.xly.u_8th.util.fmtMs
import com.xly.u_8th.util.fnv1a
import com.xly.u_8th.util.fnv1aBytesSampled
import com.xly.u_8th.util.fnv1aHex
import com.xly.u_8th.util.normSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUtilsTest {

    // ---- FNV-1a stable hash ----

    @Test
    fun `fnv1a is stable for the same input`() {
        assertEquals(fnv1a("content://media/1"), fnv1a("content://media/1"))
    }

    @Test
    fun `fnv1a differs for distinct inputs`() {
        assertNotEquals(fnv1a("/music/a.mp3"), fnv1a("/music/b.mp3"))
    }

    @Test
    fun `fnv1a matches known FNV-1a vectors`() {
        // Standard 32-bit FNV-1a test vectors.
        assertEquals(0x811c9dc5L, fnv1a(""))
        assertEquals(0xe40c292cL, fnv1a("a"))
        assertEquals(0xbf9cf968L, fnv1a("foobar"))
    }

    @Test
    fun `fnv1aHex is trimmed to length`() {
        val hex = fnv1aHex("content://media/1", 6)
        assertEquals(6, hex.length)
        assertTrue(hex.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fnv1aHex rejects a width a 32 bit hash cannot produce`() {
        // Antes: StringIndexOutOfBoundsException al recortar a 9 dígitos.
        fnv1aHex("x", 9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fnv1aBytesSampled rejects zero samples`() {
        // Antes: ArithmeticException por división entre cero.
        fnv1aBytesSampled(byteArrayOf(1, 2, 3), samples = 0)
    }

    @Test
    fun `fnv1aBytesSampled includes the trailing bytes`() {
        // Dos contenidos iguales salvo en el último byte deben diferir: el
        // salto de muestreo no Guarantee tocaría esa posición.
        val a = ByteArray(4096) { 1 }
        val b = ByteArray(4096) { 1 }.also { it[4095] = 2 }
        assertNotEquals(fnv1aBytesSampled(a), fnv1aBytesSampled(b))
    }

    // ---- Formatters ----

    // ---- Formatters ----

    @Test
    fun `fmtMs formats minutes`() {
        assertEquals("1:05", fmtMs(65_000))
    }

    @Test
    fun `fmtMs formats hours`() {
        assertEquals("1:02:03", fmtMs(3_723_000))
    }

    @Test
    fun `fmtMs formats zero`() {
        assertEquals("0:00", fmtMs(0))
    }

    @Test
    fun `fmtMs clamps negatives`() {
        // Antes devolvía "0:-5".
        assertEquals("0:00", fmtMs(-5_000))
    }

    // ---- Search normalization ----

    @Test
    fun `normSearch folds diacritics`() {
        assertEquals("cancion", normSearch("Canción"))
        assertEquals("nino", normSearch("  Niño "))
    }

    @Test
    fun `normSearch drops punctuation and collapses whitespace`() {
        assertEquals("dont stop me now", normSearch("Don't  Stop Me Now!"))
        assertEquals("a b", normSearch("  a   b  "))
    }

    @Test
    fun `normSearch folds letters outside Latin-1`() {
        assertEquals("lodz", normSearch("Łódź"))
    }

    // ---- Song display fallbacks ----

    @Test
    fun `displayTitle trims the tag title`() {
        assertEquals("Mi Título", Song(path = "/a.mp3", title = "  Mi Título  ").displayTitle)
    }

    @Test
    fun `displayTitle falls back to file name`() {
        assertEquals("my_track", Song(path = "/music/my_track.flac").displayTitle)
    }

    @Test
    fun `displayTitle never shows numeric content ids`() {
        assertEquals(
            AppStrings.UNTITLED,
            Song(path = "content://media/external/audio/media/123", contentUri = "content://media/external/audio/media/123").displayTitle,
        )
    }

    @Test
    fun `playUri prefers contentUri`() {
        assertEquals("content://1", Song(path = "/a/b.mp3", contentUri = "content://1").playUri)
    }

    @Test
    fun `isContentUri detects content scheme`() {
        assertTrue(Song(path = "content://1", contentUri = "content://1").isContentUri)
    }

    @Test
    fun `playbackUriString prefers contentUri without disk access`() {
        val song = Song(
            path = "content://media/1",
            contentUri = "content://media/1",
            filePath = "/tmp/no_existe_12345.mp3",
        )
        assertEquals("content://media/1", song.playbackUriString)
    }

    @Test
    fun `playbackUriString uses filePath when there is no contentUri`() {
        val song = Song(path = "/music/a.mp3", filePath = "/tmp/no_existe_12345.mp3")
        assertEquals("file:///tmp/no_existe_12345.mp3", song.playbackUriString)
    }

    @Test
    fun `playbackUriString escapes reserved characters in file paths`() {
        val song = Song(path = "/music/AC#DC ?100% ñ.mp3")
        assertEquals("file:///music/AC%23DC%20%3F100%25%20%C3%B1.mp3", song.playbackUriString)
    }

    @Test
    fun `queueEntryKey is stable for duplicates and reorderings`() {
        fun song(p: String) = Song(path = p, contentUri = p)
        val list = listOf(song("a"), song("b"), song("a"))
        assertEquals("a#0", Song.queueEntryKey(list, 0))
        assertEquals("b#0", Song.queueEntryKey(list, 1))
        assertEquals("a#1", Song.queueEntryKey(list, 2))
    }

    @Test
    fun `queueEntryKeys matches the per index helper`() {
        fun song(p: String) = Song(path = p, contentUri = p)
        val list = listOf(song("a"), song("b"), song("a"), song("a"))
        assertEquals(
            list.indices.map { Song.queueEntryKey(list, it) },
            Song.queueEntryKeys(list),
        )
    }
}

class MetadataFixesTest {

    @Test
    fun `vultures albums hang from Kanye West`() {
        assertEquals("Kanye West", com.xly.u_8th.data.MetadataFixes.artistFor("VULTURES 1", "¥$"))
        assertEquals("Kanye West", com.xly.u_8th.data.MetadataFixes.artistFor("VULTURES 2", "YS"))
    }

    @Test
    fun `correct tags are untouched`() {
        assertEquals(
            "Kanye West",
            com.xly.u_8th.data.MetadataFixes.artistFor("VULTURES 1", "Kanye West"),
        )
        assertEquals(
            "¥$",
            com.xly.u_8th.data.MetadataFixes.artistFor("Otro", "¥$"),
        )
    }
}
