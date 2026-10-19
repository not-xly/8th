package com.xly.u_8th.ui

import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.normSearch

/**
 * Artista encontrado: nombre visible, clave normalizada y canciones en las que
 * figura. [albumCount]: álbumes de su menú ([Artists.albumsOf]);
 * [looseSongCount]: canciones suyas fuera de esos álbumes.
 */
data class ArtistHit(
    val name: String,
    val key: String,
    val songCount: Int,
    val albumCount: Int = 0,
    val looseSongCount: Int = songCount,
)

/** Canción cuya letra en caché contiene la búsqueda; [line] es la línea que coincide. */
data class LyricHit(val albumKey: String, val song: Song, val line: String)

/** Resultados de búsqueda: artistas arriba, canciones y, debajo, coincidencias en letras. */
data class SearchResults(
    val artists: List<ArtistHit> = emptyList(),
    val songs: List<Pair<String, Song>> = emptyList(),
    val lyrics: List<LyricHit> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && songs.isEmpty() && lyrics.isEmpty()
}

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

    @Volatile
    private var artistIndex: List<ArtistHit> = emptyList()

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
        artistIndex = buildArtistIndex(idx.map { it.song }, grouped)
    }

    /** Pares (álbum, canción) del índice, en el orden del grid. */
    fun entries(): List<Pair<String, Song>> = index.map { it.albumKey to it.song }

    /** Artistas cuyo nombre normalizado contiene [query]: primero los que empiezan así, luego por canciones. */
    fun searchArtists(query: String, limit: Int = MAX_ARTIST_HITS): List<ArtistHit> =
        matchArtists(artistIndex, query, limit)

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

    companion object {
        const val MAX_ARTIST_HITS = 8

        /**
         * Artistas distintos (ver [Artists.parseArtists]) con su número de
         * canciones. Con [grouped], cuenta además los álbumes de su menú (los
         * de [Artists.albumsOf]) y las canciones suyas que no están en ellos.
         */
        fun buildArtistIndex(songs: List<Song>, grouped: Map<String, List<Song>> = emptyMap()): List<ArtistHit> {
            // Claves de artista de cada álbum, una sola vez (como albumsOf).
            val albumsByArtistKey = HashMap<String, MutableSet<String>>()
            val albumOfPath = HashMap<String, String>()
            for ((albumKey, albumSongs) in grouped) {
                for (k in Artists.albumArtistKeys(albumSongs)) {
                    albumsByArtistKey.getOrPut(k) { HashSet() }.add(albumKey)
                }
                for (s in albumSongs) albumOfPath[s.path] = albumKey
            }
            val names = LinkedHashMap<String, String>()
            val counts = HashMap<String, Int>()
            val loose = HashMap<String, Int>()
            val artistAlbums = HashMap<String, Set<String>>()
            for (song in songs) {
                for (name in Artists.parseArtists(song.artist)) {
                    val key = normSearch(name)
                    if (key.isEmpty()) continue
                    names.putIfAbsent(key, name)
                    counts[key] = (counts[key] ?: 0) + 1
                    val albums = artistAlbums.getOrPut(key) {
                        Artists.parseArtists(names.getValue(key))
                            .flatMap { albumsByArtistKey[Artists.normalize(it)] ?: emptySet() }
                            .toSet()
                    }
                    val albumKey = albumOfPath[song.path]
                    if (albumKey == null || albumKey !in albums) loose[key] = (loose[key] ?: 0) + 1
                }
            }
            return names.map { (key, name) ->
                ArtistHit(
                    name = name,
                    key = key,
                    songCount = counts[key] ?: 0,
                    albumCount = artistAlbums[key]?.size ?: 0,
                    looseSongCount = loose[key] ?: 0,
                )
            }
        }

        /**
         * Subtítulo de un artista en la búsqueda: "2 álbumes + 2 canciones";
         * omite la parte que vale cero ("" si ambas lo son).
         */
        fun artistCountLabel(albums: Int, songs: Int): String {
            val parts = ArrayList<String>(2)
            if (albums > 0) parts.add(if (albums == 1) "1 álbum" else "$albums álbumes")
            if (songs > 0) parts.add(if (songs == 1) "1 canción" else "$songs canciones")
            return parts.joinToString(" + ")
        }

        fun matchArtists(artists: List<ArtistHit>, query: String, limit: Int = MAX_ARTIST_HITS): List<ArtistHit> {
            val q = normSearch(query)
            if (q.isEmpty()) return emptyList()
            return artists
                .filter { q in it.key }
                .sortedWith(compareBy<ArtistHit> { !it.key.startsWith(q) }.thenByDescending { it.songCount })
                .take(limit)
        }
    }
}
