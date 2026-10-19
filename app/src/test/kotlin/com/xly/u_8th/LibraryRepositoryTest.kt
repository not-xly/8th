package com.xly.u_8th

import com.xly.u_8th.data.LibraryRepository
import com.xly.u_8th.data.escapeLike
import com.xly.u_8th.data.matchesFolder
import com.xly.u_8th.data.relativeFolderPath
import com.xly.u_8th.data.SongMedia
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parts only: the repository is built with a null Context/Prefs (it
 * never touches them here) so no Android runtime is needed.
 */
class LibraryRepositoryTest {

    private val repo = LibraryRepository(null, null)

    @Test
    fun `converts a full media row`() = runTest {
        val songs = repo.songsFromMedia(
            listOf(
                SongMedia(
                    uri = "content://media/1",
                    name = "song.mp3",
                    title = "Mi Canción",
                    artist = "Artista",
                    album = "Álbum",
                    track = "3/12",
                    duration = 180000L,
                    artUri = "content://art/1",
                    path = "/storage/song.mp3",
                )
            )
        )
        assertEquals(1, songs.size)
        val song = songs.single()
        assertEquals("content://media/1", song.path)
        assertEquals("content://media/1", song.contentUri)
        assertEquals("/storage/song.mp3", song.filePath)
        assertEquals("Mi Canción", song.title)
        assertEquals("Artista", song.artist)
        assertEquals("Álbum", song.album)
        assertEquals(3, song.trackNumber)
        assertEquals(180000L, song.durationMs)
        assertEquals("content://art/1", song.albumArtUri)
    }

    private suspend fun trackOf(track: Any?): Int? =
        repo.songsFromMedia(
            listOf(SongMedia(uri = "content://media/1", name = "s.mp3", track = track))
        ).single().trackNumber

    @Test
    fun `track normalization int positive zero and empty`() = runTest {
        assertEquals(5, trackOf(5))
        assertNull(trackOf(0))
        assertNull(trackOf(""))
    }

    @Test
    fun `entries without uri are dropped`() = runTest {
        val songs = repo.songsFromMedia(
            listOf(
                SongMedia(uri = "", name = "a.mp3"),
                SongMedia(uri = "content://media/2", name = "b.mp3"),
            )
        )
        assertEquals(listOf("content://media/2"), songs.map { it.path })
    }

    @Test
    fun `missing title falls back to name minus extension`() = runTest {
        val song = repo.songsFromMedia(
            listOf(SongMedia(uri = "content://media/1", name = "my_song.flac"))
        ).single()
        assertEquals("my_song", song.title)
    }

    @Test
    fun `zero duration is dropped`() = runTest {
        val song = repo.songsFromMedia(
            listOf(SongMedia(uri = "content://media/1", name = "s.mp3", duration = 0L))
        ).single()
        assertNull(song.durationMs)
    }

    // ---- Folder matching (MediaStore selection predicates) ----

    @Test
    fun `relativeFolderPath maps primary roots to relative paths`() {
        assertEquals("Music/Sub/", relativeFolderPath("/storage/emulated/0/Music/Sub"))
        assertEquals("Music/", relativeFolderPath("/sdcard/Music"))
        assertEquals("", relativeFolderPath("/storage/emulated/0"))
        assertNull(relativeFolderPath("/data/local/tmp"))
    }

    @Test
    fun `escapeLike escapes backslash percent and underscore`() {
        assertEquals("a\\\\b\\%c\\_c", escapeLike("""a\b%c_c"""))
    }

    @Test
    fun `matchesFolder accepts data or relative match and rejects others`() {
        assertTrue(matchesFolder("/storage/emulated/0/Music", "/storage/emulated/0/Music/a.mp3", "Music/a.mp3"))
        assertTrue(matchesFolder("/storage/emulated/0/Music", "/sdcard/Music/a.mp3", "Music/a.mp3"))
        assertFalse(matchesFolder("/storage/emulated/0/Music", "/storage/emulated/0/Movies/a.mp3", "Movies/a.mp3"))
        assertFalse(matchesFolder("/storage/emulated/0/Music", null, "Movies/a.mp3"))
        assertFalse(matchesFolder("/storage/emulated/0/Music", "/storage/emulated/0/MusicX/a.mp3", "MusicX/a.mp3"))
        assertTrue(matchesFolder("/storage/emulated/0", "/storage/emulated/0/a.mp3", "a.mp3"))
    }

    @Test
    fun `songsFromMedia keeps subfolder tracks under the parent folder`() = runTest {
        val rows = listOf(
            SongMedia(uri = "content://media/1", name = "cd1.mp3", path = "/storage/emulated/0/Music/CD1/01.mp3"),
            SongMedia(uri = "content://media/2", name = "root.mp3", path = "/storage/emulated/0/Music/02.mp3"),
        )
        assertEquals(listOf("cd1", "root"), repo.songsFromMedia(rows).map { it.title })
    }
}
