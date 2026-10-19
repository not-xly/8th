package com.xly.u_8th

import com.xly.u_8th.data.Artists
import com.xly.u_8th.data.LrcLogic
import com.xly.u_8th.data.LrcPrefs
import com.xly.u_8th.data.LyricsRepository
import com.xly.u_8th.data.LyricsSearch
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.LibrarySearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Búsqueda de artistas, búsqueda en letras en caché y traducción (lógica pura). */
class SearchFeaturesTest {

    private fun song(path: String, title: String, artist: String?): Song =
        Song(path = path, contentUri = path, title = title, artist = artist)

    // ---- Artist index ----

    private val library = listOf(
        song("/m/1.mp3", "Uno", "Beyoncé"),
        song("/m/2.mp3", "Dos", "Beyoncé feat. Jay-Z"),
        song("/m/3.mp3", "Tres", "JAY-Z"),
        song("/m/4.mp3", "Cuatro", "Tyler, The Creator"),
        song("/m/5.mp3", "Cinco", null),
    )

    @Test
    fun `artist index counts distinct artists across collaborations`() {
        val index = LibrarySearch.buildArtistIndex(library)
        val byKey = index.associateBy { it.key }
        assertEquals(3, index.size)
        assertEquals(2, byKey.getValue("beyonce").songCount)
        assertEquals(2, byKey.getValue("jayz").songCount)
        assertEquals("Tyler, The Creator", byKey.getValue("tyler the creator").name)
    }

    @Test
    fun `artist search ignores case and accents`() {
        val index = LibrarySearch.buildArtistIndex(library)
        assertEquals(listOf("Beyoncé"), LibrarySearch.matchArtists(index, "BEYONCE").map { it.name })
        assertEquals(listOf("Beyoncé"), LibrarySearch.matchArtists(index, "yoncé").map { it.name })
        assertTrue(LibrarySearch.matchArtists(index, "  ").isEmpty())
        assertTrue(LibrarySearch.matchArtists(index, "zzz").isEmpty())
    }

    @Test
    fun `artist search ranks prefix matches first`() {
        val index = LibrarySearch.buildArtistIndex(
            listOf(song("/a", "a", "Alicia Keys"), song("/b", "b", "Keys N Krates"), song("/c", "c", "Alicia Keys")),
        )
        assertEquals(listOf("Keys N Krates", "Alicia Keys"), LibrarySearch.matchArtists(index, "keys").map { it.name })
    }

    // ---- Artist counts (albums + loose songs) ----

    @Test
    fun `artist count label joins albums and songs`() {
        assertEquals("2 álbumes + 2 canciones", LibrarySearch.artistCountLabel(2, 2))
        assertEquals("1 álbum + 1 canción", LibrarySearch.artistCountLabel(1, 1))
    }

    @Test
    fun `artist count label omits zero parts`() {
        assertEquals("3 canciones", LibrarySearch.artistCountLabel(0, 3))
        assertEquals("1 canción", LibrarySearch.artistCountLabel(0, 1))
        assertEquals("2 álbumes", LibrarySearch.artistCountLabel(2, 0))
        assertEquals("1 álbum", LibrarySearch.artistCountLabel(1, 0))
        assertEquals("", LibrarySearch.artistCountLabel(0, 0))
    }

    @Test
    fun `songs inside the artist albums are not counted as loose songs`() {
        val grouped = linkedMapOf(
            "Man on the Moon" to listOf(song("/k/1", "a", "Kid Cudi"), song("/k/2", "b", "Kid Cudi"), song("/k/3", "c", "Kid Cudi")),
            "Indicud" to listOf(song("/k/4", "d", "Kid Cudi"), song("/k/5", "e", "Kid Cudi feat. Kanye West")),
            "Single A" to listOf(song("/k/6", "f", "Kid Cudi")),
            // Disco de otro artista con un feat. de Kid Cudi: no es suyo, la canción cuenta suelta.
            "Otro" to listOf(song("/o/1", "g", "Kanye West"), song("/o/2", "h", "Kanye West feat. Kid Cudi"), song("/o/3", "i", "Kanye West")),
        )
        val index = LibrarySearch.buildArtistIndex(grouped.values.flatten(), grouped)
        val cudi = index.single { it.key == "kid cudi" }
        assertEquals(7, cudi.songCount)
        assertEquals(Artists.albumsOf("Kid Cudi", grouped).size, cudi.albumCount)
        assertEquals(3, cudi.albumCount)
        assertEquals(1, cudi.looseSongCount)
        assertEquals("3 álbumes + 1 canción", LibrarySearch.artistCountLabel(cudi.albumCount, cudi.looseSongCount))
    }

    // ---- Lyrics search ----

    @Test
    fun `lyrics query needs three normalized characters`() {
        assertNull(LyricsSearch.normalizedQuery("ab"))
        assertNull(LyricsSearch.normalizedQuery("a!b?"))
        assertEquals("abc", LyricsSearch.normalizedQuery("ÁbC"))
    }

    @Test
    fun `line matching is accent and case insensitive and returns the original line`() {
        val lines = LyricsSearch.linesOf("[00:01.00]Corazón partío\n[00:05.00]Ya lo sé")
        assertEquals("Corazón partío", LyricsSearch.firstMatch(lines, LyricsSearch.normalizedQuery("CORAZON")!!))
        assertNull(LyricsSearch.firstMatch(lines, "nada"))
    }

    @Test
    fun `plain lyrics are searchable too`() {
        val lines = LyricsSearch.linesOf("Hello darkness my old friend\nI've come to talk")
        assertEquals("Hello darkness my old friend", LyricsSearch.firstMatch(lines, "darkness"))
    }

    @Test
    fun `file names link back to songs by base name`() {
        val s = song("/music/x.mp3", "Bad Guy", "Billie Eilish")
        val fileName = "${LrcLogic.baseNameFor(s)}.lrc"
        assertEquals(LyricsSearch.keyFor(s), LyricsSearch.keyForFileName(fileName))
        assertEquals(LyricsSearch.keyFor(s), LyricsSearch.keyForFileName(fileName.uppercase()))
        assertNull(LyricsSearch.keyForFileName("${LrcLogic.baseNameFor(s)}.es.txt"))
        val index = mapOf(LyricsSearch.keyFor(s) to LyricsSearch.linesOf("[00:01.00]So you're a tough guy"))
        val other = song("/music/y.mp3", "Other", "Someone")
        val hits = LyricsSearch.search("tough", listOf(other, s), index)
        assertEquals(listOf(s to "So you're a tough guy"), hits)
        assertTrue(LyricsSearch.search("to", listOf(s), index).isEmpty())
    }

    @Test
    fun `repository searches cached lrc files and refreshes after import`() = runBlocking {
        val dir: File = Files.createTempDirectory("lrc").toFile()
        try {
            val repo = LyricsRepository(null, object : LrcPrefs {
                override fun treeUri(): String? = null
                override fun filePaths(): List<String>? = null
            }, directory = dir)
            val a = song("/m/a.mp3", "A", "X")
            val b = song("/m/b.mp3", "B", "Y")
            File(dir, "${LrcLogic.baseNameFor(a)}.lrc").writeText("[00:01.00]Under the bridge downtown")
            assertEquals(listOf(a to "Under the bridge downtown"), repo.searchLyrics("bridge", listOf(a, b)))
            // Importar invalida la caché en memoria: la nueva letra aparece.
            assertTrue(repo.importLrc(b, "[00:02.00]Another bridge to cross"))
            assertEquals(listOf(a, b), repo.searchLyrics("bridge", listOf(a, b)).map { it.first })
            assertTrue(repo.searchLyrics("br", listOf(a, b)).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
