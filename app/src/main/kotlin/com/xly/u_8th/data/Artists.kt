package com.xly.u_8th.data

import com.xly.u_8th.domain.Song

/**
 * Utilities to parse artist tags.
 *
 * The `artist` tag often carries several artists ("Queen, David Bowie",
 * "Eminem feat. Rihanna"), but sometimes the comma is part of the name
 * ("Tyler, The Creator"). Every consumer splits the same way so menus and
 * counts stay consistent.
 */
object Artists {

    /**
     * Separators between distinct artists: comma, semicolon, slash,
     * ampersand and the feat./ft./featuring/with/vs. tags.
     * "Tyler, The Creator" is protected before splitting (see [parseArtists]).
     */
    private val SPLIT_PATTERN = Regex(
        pattern = """\s*(?:,|;|/|&|\bfeat\.?(?!\w)|\bft\.?(?!\w)|\bfeaturing\b|\bwith\b|\bvs\.?(?!\w))\s*""",
        option = RegexOption.IGNORE_CASE,
    )

    private val TYLER = Regex("""tyler\s*,\s*the\s+creator""", RegexOption.IGNORE_CASE)
    private const val TYLER_PLACEHOLDER = "Tyler\u0000The Creator"
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Splits [raw] into individual artists (visible form, deduplicated):
     * - "Queen, David Bowie" -> ["Queen", "David Bowie"]
     * - "Eminem feat. Rihanna" -> ["Eminem", "Rihanna"]
     * - "Tyler, The Creator" -> ["Tyler, The Creator"]
     * - null/empty -> []
     */
    fun parseArtists(raw: String?): List<String> {
        val trimmed = raw?.trim() ?: return emptyList()
        if (trimmed.isEmpty()) return emptyList()
        val protected = TYLER.replace(trimmed, TYLER_PLACEHOLDER)
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (part in SPLIT_PATTERN.split(protected)) {
            val p = part.trim().replace("\u0000", ", ").replace(WHITESPACE, " ")
            if (p.isEmpty()) continue
            val key = p.lowercase()
            if (seen.add(key)) out.add(p)
        }
        return out
    }

    /** Normalizes for comparison ("  QUEEN " -> "queen"). */
    fun normalize(s: String): String = s.trim().lowercase()

    /** Primary artist: the first one ("A, B & C feat. D" -> "A"). */
    fun primaryArtist(raw: String?): String? = parseArtists(raw).firstOrNull()

    /** Does [song] include [artist] among its artists? */
    fun songHasArtist(song: Song, artist: String): Boolean {
        val want = normalize(artist)
        if (want.isEmpty()) return false
        return parseArtists(song.artist).any { normalize(it) == want }
    }

    /** Does [song] include any of [artists]? */
    fun songHasAnyArtist(song: Song, artists: Iterable<String>): Boolean {
        val want = artists.map(::normalize).filter { it.isNotEmpty() }.toSet()
        if (want.isEmpty()) return false
        return parseArtists(song.artist).any { normalize(it) in want }
    }

    /**
     * Album artist: the primary artist of the first track that has one.
     * The album "belongs to" whoever opens the disc; a feat. on track 5
     * does not move it.
     */
    fun albumArtistFor(songs: List<Song>): String? {
        for (song in songs) {
            val primary = primaryArtist(song.artist) ?: continue
            return primary
        }
        return null
    }

    /**
     * Does the album belong to [wanted]'s menu? Only if the artist is the
     * primary artist of the first track (collaborating elsewhere is not
     * enough).
     */
    fun albumMatchesArtist(albumSongs: List<Song>, wanted: Iterable<String>): Boolean {
        if (albumSongs.isEmpty()) return false
        val first = primaryArtist(albumSongs.first().artist) ?: return false
        val f = normalize(first)
        return wanted.map(::normalize).contains(f)
    }
}
