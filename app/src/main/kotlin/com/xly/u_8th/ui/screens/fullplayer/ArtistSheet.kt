package com.xly.u_8th.ui.screens.fullplayer

import com.xly.u_8th.ui.components.AlbumDetailsSheet
import com.xly.u_8th.ui.components.ArtistAlbumsSheet
import androidx.compose.runtime.mutableStateOf
import com.xly.u_8th.playback.PlayerBus
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xly.u_8th.data.AlbumGrouper
import com.xly.u_8th.data.Artists
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlaybackCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Panel de artista del reproductor: el mismo [ArtistAlbumsSheet] de la
 * pantalla principal, con los álbumes agrupados desde [PlaybackCatalog]
 * (el ViewModel lo llena con la biblioteca entera, con portadas). Tocar un
 * álbum abre su ficha ([AlbumDetailsSheet]), como en la pantalla principal.
 */
@Composable
fun ArtistSheetForFullPlayer(
    artist: String,
    onDismiss: () -> Unit,
) {
    val connection = rememberPlayerConnection()
    val catalogRevision by PlaybackCatalog.revision.collectAsStateWithLifecycle()
    // Reagrupar TODO el catálogo dentro de la composición bloqueaba el hilo
    // principal; con la revisión como clave no se queda obsoleto mientras
    // el panel siga abierto.
    // Álbumes y canciones fuera de ellos, del mismo agrupado.
    val artistContent by produceState(
        initialValue = emptyList<Pair<String, List<Song>>>() to emptyList<Song>(),
        key1 = artist,
        key2 = catalogRevision,
    ) {
        value = withContext(Dispatchers.Default) {
            val grouped = AlbumGrouper.groupByAlbum(PlaybackCatalog.contents())
            Artists.albumsOf(artist, grouped) to Artists.looseSongsOf(artist, grouped)
        }
    }
    val (albums, looseSongs) = artistContent
    val queue by PlayerBus.queue.collectAsStateWithLifecycle()
    val queueIndex by PlayerBus.queueIndex.collectAsStateWithLifecycle()
    var openAlbum by remember(artist) { mutableStateOf<Pair<String, List<Song>>?>(null) }
    val album = openAlbum
    if (album == null) {
        ArtistAlbumsSheet(
            artist = remember(artist) { Artists.displayName(artist) },
            albums = albums,
            looseSongs = looseSongs,
            onOpenAlbum = { albumName, albumSongs -> openAlbum = albumName to albumSongs },
            onPlayArtist = { name, songs ->
                connection.launch { playQueue(songs, initialIndex = 0, label = name) }
            },
            onPlayArtistShuffled = { name, songs ->
                connection.launch { playQueue(songs, initialIndex = songs.indices.random(), label = "$name (Aleatorio)", shuffled = true) }
            },
            onDismiss = onDismiss,
        )
    } else {
        val (albumName, albumSongs) = album
        AlbumDetailsSheet(
            albumName = albumName,
            albumSongs = albumSongs,
            currentSongPath = queue.getOrNull(queueIndex)?.playUri,
            artistName = Artists.albumArtistFor(albumSongs),
            onArtistTap = { openAlbum = null },
            onPlayAlbum = { name, songs, idx ->
                onDismiss()
                connection.launch { playQueue(songs, initialIndex = idx, label = name) }
            },
            onPlayAlbumShuffled = { name, songs ->
                onDismiss()
                connection.launch { playQueue(songs, initialIndex = songs.indices.random(), label = "$name (Aleatorio)", shuffled = true) }
            },
            onAddToNext = { song -> connection.launch { addNext(song) } },
            onAddToEnd = { song -> connection.launch { addEnd(song) } },
            onDismiss = onDismiss,
        )
    }
}
