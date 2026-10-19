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

    /** "(feat. X)" / "[ft. X]": se quita el paréntesis antes de partir. */
    private val BRACKETED_FEAT = Regex(
        pattern = """\s*[(\[]\s*((?:feat\.?|ft\.?|featuring|with)\s[^)\]]*)[)\]]?""",
        option = RegexOption.IGNORE_CASE,
    )

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
        val protected = BRACKETED_FEAT.replace(TYLER.replace(trimmed, TYLER_PLACEHOLDER)) { " ${it.groupValues[1]}" }
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (part in SPLIT_PATTERN.split(protected)) {
            val p = unbalancedBrackets(part.trim()).replace("\u0000", ", ").replace(WHITESPACE, " ")
            if (p.isEmpty()) continue
            val key = p.lowercase()
            if (seen.add(key)) out.add(p)
        }
        return out
    }

    /** Quita "(" o ")" sueltos en los extremos que dejó un corte. */
    private fun unbalancedBrackets(p: String): String {
        var t = p
        if (t.count { it == '(' } != t.count { it == ')' }) t = t.trim('(', ')', ' ')
        if (t.count { it == '[' } != t.count { it == ']' }) t = t.trim('[', ']', ' ')
        return t
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
     * Artistas (normalizados) a los que pertenece el álbum: los acreditados
     * en más de la mitad de las pistas, así un disco conjunto ("A & B") sale
     * en el menú de ambos y una intro o un feat. sueltos no lo mueven. Si
     * nadie llega (recopilatorio), el primario más frecuente; en empate, el
     * de la primera pista.
     */
    fun albumArtistKeys(albumSongs: List<Song>): Set<String> {
        if (albumSongs.isEmpty()) return emptySet()
        val credited = HashMap<String, Int>()
        val primaries = LinkedHashMap<String, Int>()
        for (song in albumSongs) {
            val parsed = parseArtists(song.artist).map(::normalize).filter { it.isNotEmpty() }
            for (a in parsed.toSet()) credited[a] = (credited[a] ?: 0) + 1
            parsed.firstOrNull()?.let { primaries[it] = (primaries[it] ?: 0) + 1 }
        }
        val majority = credited.filterValues { it * 2 > albumSongs.size }.keys
        if (majority.isNotEmpty()) return majority
        val best = primaries.maxByOrNull { it.value } ?: return emptySet()
        return setOf(best.key)
    }

    /** Does the album belong to [wanted]'s menu? (see [albumArtistKeys]) */
    fun albumMatchesArtist(albumSongs: List<Song>, wanted: Iterable<String>): Boolean {
        val keys = albumArtistKeys(albumSongs)
        return wanted.any { normalize(it) in keys }
    }

    /**
     * Álbumes del menú de [artist] (ver [albumMatchesArtist]), en el orden
     * de [grouped]. Compartido por los paneles de artista de la pantalla
     * principal y del reproductor.
     */
    fun albumsOf(artist: String, grouped: Map<String, List<Song>>): List<Pair<String, List<Song>>> {
        val wanted = parseArtists(artist)
        return grouped.entries
            .filter { (_, songs) -> albumMatchesArtist(songs, wanted) }
            .map { it.key to it.value }
    }

    /** Nombre a mostrar: el primer artista de una cadena con colaboraciones. */
    fun displayName(artist: String): String = parseArtists(artist).firstOrNull() ?: artist.trim()
}
