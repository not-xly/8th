package com.xly.u_8th.playback

import com.xly.u_8th.domain.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry (uri -> Song). Senders populate it before handing
 * only URIs across the media3 session binder; the service consults it to
 * rebuild full [Song] objects (art, album, durations) without shipping big
 * payloads (TransactionTooLarge guard, as with the old EventChannel).
 */
object PlaybackCatalog {

    private val map = ConcurrentHashMap<String, Song>()

    /**
     * Bumped on every mutation so UI derived from [contents] (the artist
     * albums sheet) can recompute instead of showing a stale grouping.
     */
    private val _revision = MutableStateFlow(0)

    /** Changes whenever the catalog does; a key for derived UI state. */
    val revision: StateFlow<Int> = _revision

    fun putAll(songs: List<Song>) {
        for (song in songs) map[song.playUri] = song
        _revision.value++
    }

    fun put(song: Song) {
        map[song.playUri] = song
        _revision.value++
    }

    fun get(uri: String): Song? = map[uri]

    /**
     * Colas en tránsito hacia el servicio (mismo proceso). Por el binder
     * solo viaja el token: una lista de miles de URIs podía rebasar el
     * límite (~1 MB) y el álbum no sonaba.
     */
    private val staged = ConcurrentHashMap<String, List<Song>>()

    /** Guarda [songs] para el servicio; devuelve el token que las recoge. */
    fun stageQueue(songs: List<Song>): String =
        UUID.randomUUID().toString().also { staged[it] = songs }

    /** Recoge (y olvida) la cola de [token]; null si no existe. */
    fun takeStaged(token: String): List<Song>? = staged.remove(token)

    /** Registry contents (the artist albums sheet groups from here). */
    fun contents(): List<Song> = map.values.toList()

    /** Sustituye el contenido (biblioteca recargada) con una sola revisión. */
    fun replaceAll(songs: List<Song>) {
        map.clear()
        for (song in songs) map[song.playUri] = song
        _revision.value++
    }
}

/**
 * La misma canción con portada del catálogo si a esta le falta (p. ej. una
 * cola restaurada: la sesión guardada no incluye los bytes de la portada).
 */
fun Song.withCatalogArt(): Song =
    if (albumArt != null) this else PlaybackCatalog.get(playUri)?.takeIf { it.albumArt != null } ?: this
