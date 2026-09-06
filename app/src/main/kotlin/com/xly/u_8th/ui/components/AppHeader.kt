package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.R
import com.xly.u_8th.domain.ViewTab
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/**
 * Top app bar: logo + title + action buttons + overflow menu (port of
 * widgets/app_header.dart).
 */
@Composable
fun AppHeader(
    isSearching: Boolean,
    hasSongs: Boolean,
    currentTab: ViewTab,
    isReorderingAlbums: Boolean,
    selectedAlbumCount: Int,
    isSyncing: Boolean,
    syncDone: Int,
    syncTotal: Int,
    onSearchToggle: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onPlaySelected: () -> Unit,
    onPickFolder: () -> Unit,
    onShowInfo: () -> Unit,
    onToggleReorder: () -> Unit,
    onSyncLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = animatedAura()
    Box(
        modifier
            .fillMaxWidth()
            .background(EighthTheme.GlassSurface.copy(alpha = 0.42f))
            // Hairline solo abajo (BorderStroke dibujaría los 4 lados).
            .drawBottomHairline()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Logo: disco blanco sobre contenedor con el color dinámico.
            Box(
                Modifier
                    .size(38.dp)
                    .shadow(14.dp, RoundedCornerShape(12.dp), spotColor = aura.copy(alpha = 0.32f), ambientColor = Color.Transparent)
                    .shadow(10.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(alpha = 0.18f), ambientColor = Color.Transparent)
                    .background(
                        Brush.linearGradient(listOf(aura, aura.copy(alpha = 0.85f))),
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f)),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_disc),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.2.dp)),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "8th",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                color = EighthTheme.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (selectedAlbumCount > 0) {
                GlassIconButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "Aleatorio de $selectedAlbumCount álbumes",
                    active = true,
                    activeTint = aura,
                    onClick = onPlaySelected,
                )
                Spacer(Modifier.width(10.dp))
            }
            GlassIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = "Buscar",
                active = isSearching,
                enabled = hasSongs,
                onClick = { onSearchToggle?.invoke() },
            )
            Spacer(Modifier.width(10.dp))
            GlassIconButton(
                icon = Icons.Rounded.Shuffle,
                contentDescription = "Aleatorio Todo",
                enabled = hasSongs,
                onClick = { onShuffle?.invoke() },
            )
            Spacer(Modifier.width(10.dp))
            OverflowMenuButton(
                hasSongs = hasSongs,
                currentTab = currentTab,
                isReorderingAlbums = isReorderingAlbums,
                isSyncing = isSyncing,
                syncDone = syncDone,
                syncTotal = syncTotal,
                onPickFolder = onPickFolder,
                onShowInfo = onShowInfo,
                onToggleReorder = onToggleReorder,
                onSyncLyrics = onSyncLyrics,
            )
        }
    }
}

@Composable
private fun OverflowMenuButton(
    hasSongs: Boolean,
    currentTab: ViewTab,
    isReorderingAlbums: Boolean,
    isSyncing: Boolean,
    syncDone: Int,
    syncTotal: Int,
    onPickFolder: () -> Unit,
    onShowInfo: () -> Unit,
    onToggleReorder: () -> Unit,
    onSyncLyrics: () -> Unit,
) {
    val aura = animatedAura()
    var open by remember { mutableStateOf(false) }
    Box {
        // Misma caja de vidrio que GlassIconButton (36px exactos, sin tap-target extra).
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(
                    BorderStroke(0.7.dp, if (isSyncing) aura.copy(alpha = 0.28f) else EighthTheme.GlassBorder),
                    RoundedCornerShape(10.dp),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    progress = { if (syncTotal > 0) (syncDone.toFloat() / syncTotal).coerceIn(0f, 1f) else 0f },
                    strokeWidth = 2.dp,
                    color = aura,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.MoreHoriz,
                    contentDescription = "Menú",
                    tint = EighthTheme.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = EighthTheme.GlassSurface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(EighthTheme.RadiusM),
            border = BorderStroke(0.7.dp, EighthTheme.GlassBorder),
        ) {
            DropdownMenuItem(
                text = { Text("Carpeta de música", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, tint = aura, modifier = Modifier.size(18.dp)) },
                onClick = {
                    open = false
                    onPickFolder()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "Sincronizar letras",
                        fontSize = 14.sp,
                        color = if (hasSongs) EighthTheme.TextPrimary else EighthTheme.TextTertiary,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Lyrics,
                        null,
                        tint = if (hasSongs) aura else EighthTheme.TextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                enabled = hasSongs,
                onClick = {
                    open = false
                    onSyncLyrics()
                },
            )
            if (hasSongs && currentTab == ViewTab.ALBUMS) {
                DropdownMenuItem(
                    text = { Text(if (isReorderingAlbums) "Guardar orden" else "Ordenar álbumes", fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            if (isReorderingAlbums) Icons.Rounded.Check else Icons.Rounded.SwapVert,
                            null,
                            tint = aura,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        open = false
                        onToggleReorder()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Información", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Rounded.Info, null, tint = aura, modifier = Modifier.size(18.dp)) },
                onClick = {
                    open = false
                    onShowInfo()
                },
            )
        }
    }
}

/** Bottom hairline via drawBehind: matches the Dart bottom BorderSide. */
private fun Modifier.drawBottomHairline(): Modifier = this.then(
    Modifier.drawBehind {
        val stroke = 0.8.dp.toPx()
        drawRect(
            color = EighthTheme.GlassBorder,
            topLeft = Offset(0f, size.height - stroke),
            size = Size(size.width, stroke),
        )
    }
)
