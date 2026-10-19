package com.xly.u_8th

import com.xly.u_8th.ui.screens.lyrics.MAX_LRC_IMPORT_BYTES
import com.xly.u_8th.ui.screens.lyrics.lyricsResumeDelay
import com.xly.u_8th.ui.screens.lyrics.validateLrcImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsControlsLogicTest {

    @Test
    fun `validate accepts a minimal synced lrc`() {
        val text = "[00:01.50]Hola\n[01:02.3]Mundo\n"
        assertEquals(text.trim(), validateLrcImport(text))
    }

    @Test
    fun `validate strips BOM and surrounding whitespace`() {
        assertEquals("[00:01]Hola", validateLrcImport("\uFEFF  [00:01]Hola\n"))
    }

    @Test
    fun `validate rejects empty content`() {
        assertThrows(IllegalArgumentException::class.java) { validateLrcImport("   \n ") }
    }

    @Test
    fun `validate rejects content without timed lines`() {
        assertThrows(IllegalArgumentException::class.java) { validateLrcImport("solo texto sin marcas") }
    }

    @Test
    fun `validate rejects NUL bytes`() {
        assertThrows(IllegalArgumentException::class.java) { validateLrcImport("[00:01]\u0000hola") }
    }

    @Test
    fun `validate rejects content above the size limit`() {
        val big = buildString { repeat(300 * 1024) { append('a') } }
        assertThrows(IllegalArgumentException::class.java) { validateLrcImport(big) }
    }

    @Test
    fun `resume delay is zero when suppression already elapsed`() {
        assertEquals(0L, lyricsResumeDelay(nowMs = 5_000L, suppressUntilMs = 4_999L))
    }

    @Test
    fun `resume delay keeps the remaining suppression window`() {
        assertEquals(2_500L, lyricsResumeDelay(nowMs = 1_000L, suppressUntilMs = 3_500L))
    }

    @Test
    fun `import limit constant stays at 256 KiB`() {
        assertEquals(256 * 1024, MAX_LRC_IMPORT_BYTES)
        assertTrue(MAX_LRC_IMPORT_BYTES > 0)
    }
}
