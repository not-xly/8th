package com.xly.u_8th.data

import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.fnv1aHex
import java.text.Normalizer

/**
 * Pure (context-free) lyrics logic, split from [LyricsRepository] so the JVM
 * test suite can exercise naming, cleaning and LRC parsing without Android.
 */
object LrcLogic {

    // ---- File name scheme (must stay identical to the previous app version so
    // existing .lrc files keep matching) ----

    private val ILLEGAL_FILE_CHARS = Regex("""[\\/:*?"<>|]""")
    private val WHITESPACE_RUN1 = Regex("""\s+""")

    /** Removes filename-illegal characters, collapses whitespace, caps at 80 chars. */
    fun sanitize(s: String): String {
        var r = s.replace(ILLEGAL_FILE_CHARS, "_").trim()
        r = r.replace(WHITESPACE_RUN1, " ").trim()
        if (r.isEmpty()) return "unknown"
        if (r.length > MAX_BASE_LENGTH) {
            // Cortar en mitad de un par suplente deja un carácter inválido
            // en el nombre del fichero y las comparaciones de `cachedLrcNames`
            // dejan de funcionar de forma fiable.
            var end = MAX_BASE_LENGTH
            if (Character.isHighSurrogate(r[end - 1])) end--
            r = r.substring(0, end).trim()
        }
        return r
    }

    private const val MAX_BASE_LENGTH = 80

    /** "a/b/c.ext" -> "c" (no extension, leading-dot files kept whole). */
    private fun baseWithoutExtension(raw: String): String {
        val base = raw.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0) base else base.substring(0, dot)
    }

    fun baseNameFor(song: Song): String {
        val display = song.displayTitle.trim()
        val artist = (song.artist ?: "").trim()
        val base = when {
            artist.isNotEmpty() && display.isNotEmpty() -> "${sanitize(artist)} - ${sanitize(display)}"
            display.isNotEmpty() -> sanitize(display)
            else -> sanitize(baseWithoutExtension(song.filePath ?: song.path))
        }
        // Short stable hash desambigua pares con el mismo título+artista
        // (mismo nombre, fichero distinto).
        return "${base}_${fnv1aHex(song.path)}"
    }

    // ---- Title/artist cleaning for search ----

    private val FEAT_IN_TITLE = Regex(
        pattern = """\s*(?:[\(\[\{]\s*)?(?:feat\.?|ft\.?|featuring)\b[^\)\]\}]*[\)\]\}]?\s*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val NOISE_TAG = Regex(
        // \b: sin límite de palabra "live" casaba con "Olive" y "mix" con
        // "Mixture", y se borraban paréntesis que forman parte del título.
        pattern = """\b(?:remaster|remastered|live|acoustic|instrumental|version|edit|mix|mixed|remix|deluxe|bonus|anniversary|mono|stereo|karaoke|demo)\b""",
        option = RegexOption.IGNORE_CASE,
    )
    private val BRACKET_GROUP = Regex("""[\(\[\{][^\)\]\}]*[\)\]\}]""")
    private val DASH_SUFFIX = Regex(
        pattern = """\s*[-–—]\s*(?:remaster(?:ed)?\b|live\b|\S*\s?(?:version|edit|mix)\b).*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val WHITESPACE_RUN = Regex("""\s+""")
    private val EDGE_DASH = Regex("""^[-–—·]+\s*|\s*[-–—·]+$""")

    fun squash(s: String): String =
        s.replace(WHITESPACE_RUN, " ").trim().replace(EDGE_DASH, "")

    /** Strips "feat. X" and noise tags in brackets ("(Remastered 2011)", "[Live]", "- Radio Edit"). */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = FEAT_IN_TITLE.replaceFirst(t, " ")
        var changed = true
        var guard = 0
        while (changed && guard++ < 10) {
            changed = false
            // Reverse order so replacing one group does not shift earlier ranges.
            for (g in BRACKET_GROUP.findAll(t).toList().asReversed()) {
                if (NOISE_TAG.containsMatchIn(g.value)) {
                    t = t.replaceRange(g.range, " ")
                    changed = true
                }
            }
            val t2 = DASH_SUFFIX.replaceFirst(t, " ")
            if (t2 != t) {
                t = t2
                changed = true
            }
        }
        val cleaned = squash(t)
        return cleaned.ifEmpty { raw.trim() }
    }

    /** First primary artist ("A, B & C feat. D" -> "A"); respects "Tyler, The Creator". */
    fun cleanArtist(raw: String): String = Artists.primaryArtist(raw) ?: raw.trim()

    // ---- Fuzzy matching (normalized contains) ----

    /**
     * Letters + digits kept as-is (Latin, CJK, Kana, Hangul), everything
     * else dropped. Hoisted: this runs a few times per candidate during a NetEase
     * search, and compiling the class each time showed up in
     * bulk-sync times.
     */
    private val NON_MATCH_CHARS =
        Regex("""[^a-z0-9\u0370-\u03ff\u0400-\u04ff\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")

    /**
     * Las tildes se pliegan (NFD + quitar diacríticos): antes se borraba la
     * letra entera, así que "Canción" ("cancin") no casaba con "Cancion".
     * Hangul se recompone (NFC) para que el rango de sílabas siga valiendo.
     */
    fun normForMatch(s: String): String {
        val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        return Normalizer.normalize(folded, Normalizer.Form.NFC).replace(NON_MATCH_CHARS, "")
    }

    /** ¿Tiene al menos una marca de tiempo LRC (letra sincronizada)? */
    fun hasTimestamps(content: String): Boolean = LRC_TIMESTAMP.containsMatchIn(content)

    fun fuzzyContains(haystack: String, needle: String): Boolean {
        val n = normForMatch(needle)
        if (n.isEmpty()) return true
        val h = normForMatch(haystack)
        return h.isNotEmpty() && h.contains(n)
    }

    // ---- LRC parsing ----

    private val LRC_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val TAG_LINE = Regex("""^\[(ti|ar|al|by|offset):""", RegexOption.IGNORE_CASE)
    private val OFFSET_TAG = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*\]\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Supports multiple timestamps per line and [mm:ss.xx]; returns lines sorted by time.
     * With [applyOffsetTag] the [offset:] tag shifts every timestamp, applied
     * as `timestamp + offset` to match [LyricsRepository.getOffsetMs] and the
     * UI ("adelantar letras" = offset positivo = aparecen antes).
     */
    fun parseLrc(content: String, applyOffsetTag: Boolean = false): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        var offsetMs = 0L
        for (rawLine in content.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (applyOffsetTag) {
                val tag = OFFSET_TAG.find(line)
                if (tag != null) {
                    offsetMs = tag.groupValues[1].toLongOrNull() ?: 0L
                    continue
                }
            }
            if (TAG_LINE.containsMatchIn(line)) continue
            val matches = LRC_TIMESTAMP.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.replace(LRC_TIMESTAMP, "").trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val frac = m.groupValues[3]
                val millis = when (frac.length) {
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    3 -> frac.toLong()
                    else -> 0L
                }
                lines.add(LrcLine(timestampMs = (min * 60 + sec) * 1000L + millis + offsetMs.coerceAtLeast(-9_000_000L), text = text))
            }
        }
        lines.sortBy { it.timestampMs }
        return lines
    }

    /** Plain (unsynced) lyrics: every non-empty line becomes a line at timestamp 0. */
    fun plainLines(content: String): List<LrcLine> =
        content.split('\n')
            .filter { it.trim().isNotEmpty() }
            .map { LrcLine(0, it.trim()) }
}
