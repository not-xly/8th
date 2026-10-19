package com.xly.u_8th.ui.components

import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import com.xly.u_8th.util.AppStrings

/**
 * Espera tras cerrar el sheet antes del trabajo pesado de reproducir: sin
 * esto compite con la animación de bajada.
 */
const val SHEET_PLAY_DELAY_MS = 200L

/** Sheet de detalle de álbum. */
@Composable
fun AlbumDetailsSheet(
    albumName: String,
    albumSongs: List<Song>,
    currentSongPath: String?,
    artistName: String?,
    onArtistTap: (String) -> Unit,
    onPlayAlbum: (String, List<Song>, Int) -> Unit,
    onPlayAlbumShuffled: (String, List<Song>) -> Unit,
    onAddToNext: (Song) -> Unit,
    onAddToEnd: (Song) -> Unit,
    onDismiss: () -> Unit,
    /** Canciones a añadir a una playlist (una o el álbum entero); null lo oculta. */
    onAddToPlaylist: ((List<Song>) -> Unit)? = null,
) {
    val aura = animatedAura()
    GlassBottomSheet(onDismiss = onDismiss, heightFraction = 1f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val cover = rememberArtBitmap(albumSongs.firstOrNull { it.albumArt != null }?.albumArt, 76.dp)
            Box(
                Modifier
                    .size(76.dp)
                    .shadow(12.dp, RoundedCornerShape(14.dp), spotColor = aura.copy(alpha = 0.4f), ambientColor = Color.Transparent)
                    .clip(RoundedCornerShape(14.dp))
                    .background(EighthTheme.GlassSurfaceLight)
                    .border(BorderStroke(0.7.dp, EighthTheme.GlassBorderStrong), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.Album, null, tint = aura, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    albumName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = EighthTheme.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Artista clicable -> álbumes del artista. Se guarda el valor YA
                // recortado: `canTapArtist` no permite al compilador
                // hacer smart-cast del parámetro capturado (era un `!!`).
                val tappedArtist = artistName?.trim().orEmpty()
                val canTapArtist = tappedArtist.isNotEmpty()
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .pressClickable(enabled = canTapArtist, pressedScale = 0.95f) { onArtistTap(tappedArtist) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        artistName ?: AppStrings.UNKNOWN,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (canTapArtist) EighthTheme.accent(aura) else EighthTheme.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (canTapArtist) {
                        Spacer(Modifier.width(1.dp))
                        Icon(Icons.Rounded.ChevronRight, null, tint = EighthTheme.accent(aura), modifier = Modifier.size(16.dp))
                    }
                }
                val totalMs = albumSongs.sumOf { it.durationMs ?: 0L }
                val count = albumSongs.size
                Text(
                    buildString {
                        append(if (count == 1) "1 canción" else "$count canciones")
                        if (totalMs > 0) append(" · ").append(totalMs / 60_000).append(" min")
                    },
                    fontSize = 12.sp,
                    color = EighthTheme.TextTertiary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row {
            PillButton(
                label = "Reproducir",
                onClick = {
                    onDismiss()
                    onPlayAlbum(albumName, albumSongs, 0)
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.PlayArrow,
                primary = true,
            )
            Spacer(Modifier.width(10.dp))
            PillButton(
                label = "Aleatorio",
                onClick = {
                    onDismiss()
                    onPlayAlbumShuffled(albumName, albumSongs)
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Shuffle,
            )
            if (onAddToPlaylist != null) {
                Spacer(Modifier.width(10.dp))
                GlassIconButton(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = "Añadir álbum a playlist",
                    onClick = { onAddToPlaylist(albumSongs) },
                    size = EighthTheme.IconButtonM,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetDivider()
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            // `key` por canción: sin él, el estado interno de SongTile
            // (menú desplegable, decodificación de portada) se reasigna
            // por posición al cambiar la lista.
            items(
                count = albumSongs.size,
                key = { index -> albumSongs[index].stableId },
            ) { index ->
                val song = albumSongs[index]
                val isCurrent = currentSongPath != null && currentSongPath == song.playUri
                SongTile(
                    song = song,
                    isCurrent = isCurrent,
                    onTap = {
                        onDismiss()
                        onPlayAlbum(albumName, albumSongs, index)
                    },
                    onAddToNext = { onAddToNext(song) },
                    onAddToEnd = { onAddToEnd(song) },
                    onAddToPlaylist = onAddToPlaylist?.let { add -> { add(listOf(song)) } },
                )
            }
        }
    }
}

/** Sheet de álbumes de un artista. */
@Composable
fun ArtistAlbumsSheet(
    artist: String,
    albums: List<Pair<String, List<Song>>>,
    onOpenAlbum: (String, List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassBottomSheet(onDismiss = onDismiss, heightFraction = 0.6f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIconBadge(Icons.Rounded.Person, animatedAura())
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    artist,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    color = EighthTheme.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${albums.size} ${if (albums.size == 1) "álbum" else "álbumes"}",
                    fontSize = 12.sp,
                    color = EighthTheme.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetDivider()
        Spacer(Modifier.height(12.dp))
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Sin álbumes de este artista",
                    color = EighthTheme.TextSecondary,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(
                    count = albums.size,
                    key = { index -> albums[index].first },
                ) { index ->
                    val (albumName, albumSongs) = albums[index]
                    AlbumRow(
                        albumName = albumName,
                        albumSongs = albumSongs,
                        onClick = { onOpenAlbum(albumName, albumSongs) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    albumName: String,
    albumSongs: List<Song>,
    onClick: () -> Unit,
) {
    val art = rememberArtBitmap(albumSongs.firstOrNull { it.albumArt != null }?.albumArt, targetSize = 46.dp)
    Box(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressClickable(pressedScale = 0.97f, onClick = onClick)
                .glassCard()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    Modifier
                        .size(46.dp)
                        .background(EighthTheme.GlassSurfaceLight.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                        .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Album, null, tint = EighthTheme.TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    albumName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    letterSpacing = (-0.2).sp,
                    color = EighthTheme.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${albumSongs.size} ${if (albumSongs.size == 1) "canción" else "canciones"}",
                    color = EighthTheme.TextSecondary,
                    fontSize = 12.sp,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = EighthTheme.TextTertiary, modifier = Modifier.size(20.dp))
        }
    }
}
