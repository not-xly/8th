package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * esto compite con la animación de bajada (port de AlbumDetailsSheet).
 */
const val SHEET_PLAY_DELAY_MS = 200L

/** Sheet de detalle de álbum (port of widgets/album_details_sheet.dart). */
@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val aura = animatedAura()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = EighthTheme.GlassSurface.copy(alpha = 0.78f),
        scrimColor = Color.Black.copy(alpha = 0.32f),
        shape = RoundedCornerShape(topStart = EighthTheme.RadiusL, topEnd = EighthTheme.RadiusL),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.72f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(aura.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .border(BorderStroke(0.8.dp, aura.copy(alpha = 0.16f)), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Album, null, tint = aura, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        albumName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp,
                        color = EighthTheme.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Artista clicable -> álbumes del artista.
                    val canTapArtist = !artistName.isNullOrEmpty() && artistName.trim().isNotEmpty()
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = canTapArtist,
                            ) { onArtistTap(artistName!!.trim()) }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Person,
                            null,
                            tint = aura.copy(alpha = 0.9f),
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            artistName ?: AppStrings.UNKNOWN,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (canTapArtist) aura.copy(alpha = 0.9f) else EighthTheme.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (canTapArtist) {
                            Spacer(Modifier.width(1.dp))
                            Icon(Icons.Rounded.ChevronRight, null, tint = aura, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Box(
                    Modifier
                        .shadow(10.dp, RoundedCornerShape(12.dp), spotColor = aura.copy(alpha = 0.24f), ambientColor = Color.Transparent)
                        .background(aura.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onDismiss()
                            onPlayAlbum(albumName, albumSongs, 0)
                        }
                        .padding(6.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, "Reproducir orden original", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .border(BorderStroke(0.8.dp, EighthTheme.GlassBorder), RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onDismiss()
                            onPlayAlbumShuffled(albumName, albumSongs)
                        }
                        .padding(8.dp),
                ) {
                    Icon(Icons.Rounded.Shuffle, "Reproducir aleatorio", tint = EighthTheme.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(EighthTheme.GlassBorder),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(albumSongs.size) { index ->
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
                    )
                }
            }
        }
    }
}

/** Sheet de álbumes de un artista (port del _showArtistAlbums del screen Dart). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistAlbumsSheet(
    artist: String,
    albums: List<Pair<String, List<Song>>>,
    onOpenAlbum: (String, List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = EighthTheme.GlassSurface.copy(alpha = 0.78f),
        scrimColor = Color.Black.copy(alpha = 0.32f),
        shape = RoundedCornerShape(topStart = EighthTheme.RadiusL, topEnd = EighthTheme.RadiusL),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.60f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(EighthTheme.Primary.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .border(
                            BorderStroke(0.8.dp, EighthTheme.Primary.copy(alpha = 0.16f)),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Person, null, tint = EighthTheme.Primary, modifier = Modifier.size(18.dp))
                }
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(EighthTheme.GlassBorder),
            )
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
                    items(albums.size) { index ->
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
}

@Composable
private fun AlbumRow(
    albumName: String,
    albumSongs: List<Song>,
    onClick: () -> Unit,
) {
    val art = rememberArtBitmap(albumSongs.firstOrNull { it.albumArt != null }?.albumArt, sample = 2)
    Box(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(EighthTheme.GlassSurface.copy(alpha = 0.58f), RoundedCornerShape(EighthTheme.RadiusM))
                .border(BorderStroke(0.8.dp, EighthTheme.GlassBorder), RoundedCornerShape(EighthTheme.RadiusM))
                .clickable(
                    interactionSource = remember(albumName) { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (art != null) {
                androidx.compose.foundation.Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
