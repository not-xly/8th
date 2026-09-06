package com.xly.u_8th.util

/**
 * Normalizes text for search: lowercases and folds diacritics in a single
 * pass, so "canción" matches "cancion".
 */
private val DIACRITICS = mapOf(
    'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a', 'ã' to 'a',
    'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
    'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o', 'õ' to 'o',
    'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
    'ñ' to 'n', 'ç' to 'c', 'ý' to 'y', 'ÿ' to 'y',
)

fun normSearch(s: String): String {
    val sb = StringBuilder(s.trim().length)
    for (ch in s.trim().lowercase()) {
        sb.append(DIACRITICS[ch] ?: ch)
    }
    return sb.toString()
}
