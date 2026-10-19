package com.xly.u_8th.util

/**
 * Normalización para búsqueda: minúsculas, sin diacríticos, sin puntuación
 * y con espacios colapsados. Así `"Canción  del  CORAZÓN!"` se busca como
 * `"cancion del corazon"` y un apóstrofo tipográfico (`don’t`) encuentra la
 * etiqueta que lleva el simple (`don't`).
 *
 * Dos niveles por coste:
 * - [FOLD] (array indexado por char code) cubre Latin-1, donde están la
 *   mayoría de diacríticos en etiquetas de música.
 * - [FOLD_LONG] cubre el resto (ł, œ, š…). Se consulta raramente.
 */

/** Cubre los caracteres con tilde del español y los vecinos habituales. */
private const val FOLD_LIMIT = 0xFF

private val FOLD = CharArray(FOLD_LIMIT + 1).also { table ->
    fun fold(from: String, to: Char) {
        for (c in from) {
            if (c.code <= FOLD_LIMIT) table[c.code] = to
        }
    }
    fold("áàäâãå", 'a')
    fold("éèëê", 'e')
    fold("íìïî", 'i')
    fold("óòöôõø", 'o')
    fold("úùüû", 'u')
    fold("ñ", 'n')
    fold("ç", 'c')
    fold("ýÿ", 'y')
}

/** Letras acentuadas fuera de Latin-1. */
private val FOLD_LONG: Map<Char, Char> = buildMap {
    fun fold(from: String, to: Char) {
        for (c in from) put(c, to)
    }
    fold("āăą", 'a')
    fold("ćĉċč", 'c')
    fold("ďđ", 'd')
    fold("ēĕėęě", 'e')
    fold("ĝğġģ", 'g')
    fold("ĥħ", 'h')
    fold("īĭįı", 'i')
    fold("ĵ", 'j')
    fold("ķ", 'k')
    fold("ĺļľŀł", 'l')
    fold("ñńņňŉ", 'n')
    fold("ōŏő", 'o')
    fold("ŕŗř", 'r')
    fold("śŝşš", 's')
    fold("ţťŧ", 't')
    fold("ũūŭůűų", 'u')
    fold("ŵ", 'w')
    fold("ýŷÿ", 'y')
    fold("źżž", 'z')
    fold("æ", 'a')
    fold("œ", 'o')
    fold("ß", 's')
    fold("þ", 't')
    fold("ð", 'd')
}

/**
 * Separadores (espacios y friends): se colapsan a un único espacio, en vez
 * de desaparecer, para que "ac dc" siga encontrándose en "AC/DC".
 */
private fun isSeparator(ch: Char): Boolean = ch.isWhitespace()

fun normSearch(s: String): String {
    val sb = StringBuilder(s.length)
    var pendingSpace = false
    for (raw in s) {
        val ch = raw.lowercaseChar()
        if (isSeparator(ch)) {
            if (sb.isNotEmpty()) pendingSpace = true
            continue
        }
        // El resto de puntuación no aporta nada y partiría la palabra.
        if (!ch.isLetterOrDigit()) continue
        if (pendingSpace) {
            sb.append(' ')
            pendingSpace = false
        }
        val folded = when {
            ch.code <= FOLD_LIMIT && FOLD[ch.code].code != 0 -> FOLD[ch.code]
            else -> FOLD_LONG[ch] ?: ch
        }
        sb.append(folded)
    }
    return sb.toString()
}