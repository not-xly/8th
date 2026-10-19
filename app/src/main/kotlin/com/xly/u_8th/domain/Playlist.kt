package com.xly.u_8th.domain

/**
 * Lista de reproducción del usuario. Guarda rutas ([Song.path], la identidad
 * de una canción) y no los Song: la biblioteca se re-escanea y los
 * metadatos/portadas se resuelven contra ella al mostrarla.
 */
data class Playlist(
    val id: String,
    val name: String,
    val songPaths: List<String> = emptyList(),
)

/**
 * Codec plano para SharedPreferences, sin JSON (testeable en la JVM): una
 * playlist por registro y sus campos (id, nombre, rutas…) separados por
 * caracteres de control que no aparecen en rutas ni nombres saneados.
 */
object PlaylistCodec {
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'

    fun encode(playlists: List<Playlist>): String =
        playlists.joinToString(RECORD.toString()) { p ->
            (listOf(p.id, cleanName(p.name)) + p.songPaths).joinToString(FIELD.toString())
        }

    fun decode(raw: String?): List<Playlist> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RECORD).mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size < 2 || fields[0].isEmpty()) return@mapNotNull null
            Playlist(fields[0], fields[1], fields.drop(2).filter { it.isNotEmpty() })
        }
    }

    /** Nombre en una línea y sin los separadores del codec. */
    fun cleanName(name: String): String =
        name.replace(Regex("[\\u0000-\\u001F]"), " ").replace(Regex("\\s+"), " ").trim()
}
