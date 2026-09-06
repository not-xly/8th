package com.xly.u_8th

import com.xly.u_8th.data.LrcLine
import com.xly.u_8th.data.LrcLogic
import com.xly.u_8th.domain.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the lyrics port (pure logic lives in [LrcLogic];
 * file/dir functions need a Context and are not covered here).
 */
class LyricsRepositoryTest {

    private fun song(
        path: String,
        title: String? = null,
        artist: String? = null,
        filePath: String? = null,
    ): Song = Song(
        path = path,
        contentUri = path,
        filePath = filePath,
        title = title,
        artist = artist,
    )

    // ---- Stable naming ----

    @Test
    fun `baseNameFor is deterministic for equal inputs`() {
        val a = LrcLogic.baseNameFor(song("/music/a/song.mp3", title = "Title", artist = "Artist"))
        val b = LrcLogic.baseNameFor(song("/music/a/song.mp3", title = "Title", artist = "Artist"))
        assertEquals(a, b)
    }

    @Test
    fun `same title and artist on different paths get different names`() {
        val a = LrcLogic.baseNameFor(song("/music/a/one.mp3", title = "Title", artist = "Artist"))
        val b = LrcLogic.baseNameFor(song("/music/b/two.mp3", title = "Title", artist = "Artist"))
        assertNotEquals(a, b)
        assertTrue(a.startsWith("Artist - Title_"))
        assertTrue(b.startsWith("Artist - Title_"))
    }

    @Test
    fun `baseNameFor falls back to sanitized file basename without extension`() {
        val name = LrcLogic.baseNameFor(song("/music/01 - Cool Song.mp3"))
        assertTrue(name.startsWith("01 - Cool Song_"))
        assertFalse(name.contains(".mp3"))
    }

    // ---- parseLrc ----

    @Test
    fun `parseLrc supports multiple timestamps per line and sorts them`() {
        val lines = LrcLogic.parseLrc("[01:45.30][00:12.00]hello")
        assertEquals(2, lines.size)
        assertEquals(12000L, lines[0].timestampMs)
        assertEquals(105300L, lines[1].timestampMs)
        assertEquals("hello", lines[0].text)
        assertEquals("hello", lines[1].text)
    }

    @Test
    fun `parseLrc reads seconds without fraction`() {
        val lines = LrcLogic.parseLrc("[00:05]x")
        assertEquals(1, lines.size)
        assertEquals(5000L, lines[0].timestampMs)
        assertEquals("x", lines[0].text)
    }

    @Test
    fun `parseLrc skips tag lines`() {
        val lines = LrcLogic.parseLrc("[ti:Song]\n[ar:Artist]\n[offset:500]\n[00:05]x")
        assertEquals(1, lines.size)
        assertEquals(5000L, lines[0].timestampMs)
    }

    @Test
    fun `parseLrc returns empty for plain text without timestamps`() {
        assertEquals(emptyList<LrcLine>(), LrcLogic.parseLrc("just text\nno times here"))
    }

    @Test
    fun `parseLrc scales fractions by digit count`() {
        assertEquals(500L, LrcLogic.parseLrc("[00:00.5]a")[0].timestampMs)
        assertEquals(250L, LrcLogic.parseLrc("[00:00.25]b")[0].timestampMs)
        assertEquals(123L, LrcLogic.parseLrc("[00:00.123]c")[0].timestampMs)
        assertEquals(60000L, LrcLogic.parseLrc("[01:00]d")[0].timestampMs)
    }

    // ---- cleanTitle / cleanArtist ----

    @Test
    fun `cleanTitle strips remaster live feat and edit suffixes`() {
        assertEquals("Song", LrcLogic.cleanTitle("Song (Remastered 2011)"))
        assertEquals("Song", LrcLogic.cleanTitle("Song [Live]"))
        assertEquals("Song", LrcLogic.cleanTitle("Song feat. X"))
        assertEquals("Song", LrcLogic.cleanTitle("Song - Radio Edit"))
    }

    @Test
    fun `cleanTitle keeps the core title untouched`() {
        assertEquals("Let It Be", LrcLogic.cleanTitle("Let It Be"))
        assertEquals("AC-DC", LrcLogic.cleanTitle("AC-DC"))
    }

    @Test
    fun `cleanArtist keeps Tyler, The Creator intact`() {
        assertEquals("Tyler, The Creator", LrcLogic.cleanArtist("Tyler, The Creator"))
    }

    @Test
    fun `cleanArtist returns only the primary artist`() {
        assertEquals("A", LrcLogic.cleanArtist("A, B & C feat. D"))
    }

    // ---- Plain lyrics fallback ----

    @Test
    fun `plainLines maps non-empty lines to unsynced entries`() {
        val lines = LrcLogic.plainLines("first\n\nsecond  \n   ")
        assertEquals(listOf(LrcLine(0, "first"), LrcLine(0, "second")), lines)
    }

    @Test
    fun `plainLines on blank content is empty`() {
        assertTrue(LrcLogic.plainLines("  \n\t\n").isEmpty())
    }

    // ---- Fuzzy matching ----

    @Test
    fun `fuzzyContains ignores punctuation and case but drops non-ASCII letters`() {
        assertTrue(LrcLogic.fuzzyContains("Hello — World, 123!", "hello world 123"))
        assertTrue(LrcLogic.fuzzyContains("周杰伦 - 双色", "双色"))
        assertTrue(LrcLogic.fuzzyContains("ブルー - 아이유", "ブルー"))
        assertTrue(LrcLogic.fuzzyContains("anything", ""))
        assertFalse(LrcLogic.fuzzyContains("hello", "hello world"))
        // Accented letters are removed, not transliterated (same as the Dart original).
        assertFalse(LrcLogic.fuzzyContains("Café", "cafe"))
    }
}
