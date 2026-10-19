package com.xly.u_8th.data

import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.normSearch

/**
 * Lógica pura de la búsqueda en letras ya guardadas en disco (sin Android):
 * enlaza ficheros .lrc con canciones por su nombre base y busca la primera
 * línea que contiene la consulta normalizada.
 */
object LyricsSearch {

    /** Mínimo de caracteres normalizados: con menos, casi todas las letras coinciden. */
    const val MIN_QUERY_CHARS = 3

    /** Línea de letra: texto visible y su forma normalizada para comparar. */
    class Line(val text: String, val norm: String)

    /** Consulta normalizada, o null si es demasiado corta para buscar en letras. */
    fun normalizedQuery(query: String): String? =
        normSearch(query).takeIf { it.length >= MIN_QUERY_CHARS }

    /** Clave de fichero de [song]: el nombre del .lrc sin extensión, en minúsculas. */
    fun keyFor(song: Song): String = LrcLogic.baseNameFor(song).lowercase()

    /** Clave de un nombre de fichero ("A - B_1a2b.lrc" -> "a - b_1a2b"); null si no es .lrc. */
    fun keyForFileName(fileName: String): String? {
        val lower = fileName.lowercase()
        if (!lower.endsWith(".lrc")) return null
        return lower.removeSuffix(".lrc")
    }

    /** Líneas de texto de una letra (sincronizada o no), normalizadas. */
    fun linesOf(content: String): List<Line> {
        val parsed = LrcLogic.parseLrc(content).ifEmpty { LrcLogic.plainLines(content) }
        val seen = HashSet<String>()
        val out = ArrayList<Line>(parsed.size)
        for (l in parsed) {
            val norm = normSearch(l.text)
            // Los estribillos repetidos no aportan: una sola copia por letra.
            if (norm.isNotEmpty() && seen.add(norm)) out.add(Line(l.text, norm))
        }
        return out
    }

    /** Primera línea que contiene [normQuery] (ya normalizada), o null. */
    fun firstMatch(lines: List<Line>, normQuery: String): String? =
        lines.firstOrNull { normQuery in it.norm }?.text

    /**
     * Canciones de [songs] cuya letra (en [index], clave -> líneas) contiene
     * [query], con la línea que coincide. Vacío si la consulta es corta.
     */
    fun search(
        query: String,
        songs: List<Song>,
        index: Map<String, List<Line>>,
        limit: Int = Int.MAX_VALUE,
    ): List<Pair<Song, String>> {
        val q = normalizedQuery(query) ?: return emptyList()
        val out = ArrayList<Pair<Song, String>>()
        for (song in songs) {
            val lines = index[keyFor(song)] ?: continue
            val line = firstMatch(lines, q) ?: continue
            out.add(song to line)
            if (out.size >= limit) break
        }
        return out
    }
}
