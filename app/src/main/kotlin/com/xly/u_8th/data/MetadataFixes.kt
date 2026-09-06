package com.xly.u_8th.data

/**
 * Manual corrections for known mis-tagged metadata.
 *
 * Tags come from files (MediaStore or direct reads) and sometimes carry
 * known errors that no parsing can guess. Explicit, conservative table:
 * only the exact case is corrected; correct tags are never touched.
 */
object MetadataFixes {

    /** Album (lowercased) -> correct artist. */
    private val ARTIST_BY_ALBUM = mapOf(
        "vultures 1" to "Kanye West",
        "vultures 2" to "Kanye West",
    )

    private val WHITESPACE = Regex("""\s+""")

    private fun isYsTag(artist: String): Boolean {
        val n = artist.trim().lowercase().replace(WHITESPACE, "")
        return n == "¥\$" || n == "y\$" || n == "ys" || n == "¥s" || n == "¥" || n == "\$"
    }

    /**
     * Returns the corrected artist for [album]+[artist], or [artist] as-is
     * when no correction applies. Only substitutes when the current tag is
     * the known-wrong one (or empty).
     */
    fun artistFor(album: String?, artist: String?): String? {
        if (album == null) return artist
        val fixed = ARTIST_BY_ALBUM[album.trim().lowercase()] ?: return artist
        val current = artist?.trim() ?: ""
        if (current.isEmpty() || isYsTag(current)) return fixed
        return artist
    }
}
