package com.xly.u_8th.ui

import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.normSearch

/**
 * Índice de búsqueda por título, artista y álbum, con los textos ya
 * normalizados. Se reconstruye en cada carga de biblioteca.
 */
class LibrarySearch {

    private class Entry(
        val song: Song,
        val albumKey: String,
        val title: String,
        val artist: String,
        val album: String,
    )

    // Se reconstruye y consulta fuera del hilo principal.
    @Volatile
    private var index: List<Entry> = emptyList()

    /** Mismo orden que el grid ([sortedAlbumKeys]) para conservar el orden. */
    fun rebuild(sortedAlbumKeys: List<String>, grouped: Map<String, List<Song>>, sizeHint: Int) {
        val idx = ArrayList<Entry>(sizeHint)
        for (albumKey in sortedAlbumKeys) {
            val albumNorm = normSearch(albumKey)
            for (s in grouped[albumKey] ?: emptyList()) {
                idx.add(
                    Entry(
                        song = s,
                        albumKey = albumKey,
                        title = normSearch(s.displayTitle),
                        artist = normSearch(s.artist ?: ""),
                        album = albumNorm,
                    )
                )
            }
        }
        index = idx
    }

    /** Pares (álbum, canción) que contienen [query]; la canción lleva la portada de [grouped]. */
    fun search(query: String, grouped: Map<String, List<Song>>): List<Pair<String, Song>> {
        val q = normSearch(query)
        if (q.isEmpty()) return emptyList()
        // Un solo recorrido: antes se filtraba y luego se mapeaba, con dos
        // listas intermedias para todo el índice.
        val out = ArrayList<Pair<String, Song>>()
        for (entry in index) {
            if (q in entry.title || q in entry.artist || q in entry.album) {
                // El índice se construye antes de cargar portadas: se toma la
                // del álbum en `grouped` para que la fila (y "añadir a la
                // cola") la lleve.
                val song = entry.song
                val art = if (song.albumArt != null) null else grouped[entry.albumKey]?.firstOrNull()?.albumArt
                out.add(entry.albumKey to (if (art != null) song.copy(albumArt = art) else song))
            }
        }
        return out
    }
}
