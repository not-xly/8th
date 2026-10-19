package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
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
 * Top app bar: logo + title + action buttons + overflow menu.
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
    onSearchToggle: () -> Unit,
    onShuffle: () -> Unit,
    onPlaySelected: () -> Unit,
    onPickFolder: () -> Unit,
    onShowInfo: () -> Unit,
    onToggleReorder: () -> Unit,
    onSyncLyrics: () -> Unit,
    gridColumns: Int?,
    onSetGridColumns: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = animatedAura()
    // Transparente: el fondo ambiental (carátula + aura) se ve detrás.
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
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
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
                color = EighthTheme.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (selectedAlbumCount > 0) {
                // Acción contextual destacada, fuera de la cápsula.
                Box(
                    Modifier
                        .size(HeaderButtonSize)
                        .shadow(14.dp, CircleShape, spotColor = aura.copy(alpha = 0.45f), ambientColor = Color.Transparent)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(aura, aura.copy(alpha = 0.78f))))
                        .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.22f)), CircleShape)
                        .pressClickable(pressedScale = 0.88f, onClick = onPlaySelected),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Aleatorio de $selectedAlbumCount álbumes",
                        tint = Color.White,
                        modifier = Modifier.size(HeaderIconSize),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            HeaderToolbar {
                ToolbarButton(
                    index = 0,
                    icon = Icons.Rounded.Search,
                    contentDescription = "Buscar",
                    active = isSearching,
                    enabled = hasSongs,
                    onClick = onSearchToggle,
                )
                ToolbarButton(
                    index = 1,
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "Aleatorio Todo",
                    enabled = hasSongs,
                    onClick = onShuffle,
                )
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
                    gridColumns = gridColumns,
                    onSetGridColumns = onSetGridColumns,
                )
            }
        }
    }
}

private val HeaderButtonSize = 40.dp
private val HeaderIconSize = 21.dp

/**
 * Qué botón de la cápsula manda: el que se está pulsando, el recién pulsado
 * (un instante, para que el gesto se vea aunque el toque sea corto) o el
 * que está activo (búsqueda abierta, menú desplegado). Ese crece y los
 * demás encogen.
 */
@Stable
private class ToolbarFocus {
    var pressed by mutableStateOf<Int?>(null)
    var pulse by mutableStateOf<Int?>(null)
    val active = mutableStateMapOf<Int, Boolean>()
    val focused: Int? get() = pressed ?: pulse ?: active.entries.firstOrNull { it.value }?.key
}

private val LocalToolbarFocus = compositionLocalOf { ToolbarFocus() }

/**
 * Cápsula de cristal que agrupa las acciones de la cabecera: degradado
 * vertical con brillo arriba, borde fino y sombra suave, como las barras
 * flotantes de iOS.
 */
@Composable
private fun HeaderToolbar(content: @Composable RowScope.() -> Unit) {
    val focus = remember { ToolbarFocus() }
    CompositionLocalProvider(LocalToolbarFocus provides focus) {
        Row(
            Modifier
                .height(HeaderButtonSize + 4.dp)
                .shadow(18.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.4f), ambientColor = Color.Transparent)
                .glass()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Botón de la cápsula: sin caja propia, solo un halo. Con muelle, el
 * enfocado se ensancha y agranda el icono mientras los otros encogen.
 */
@Composable
private fun ToolbarButton(
    index: Int,
    icon: ImageVector?,
    contentDescription: String?,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val aura = animatedAura()
    val focus = LocalToolbarFocus.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) focus.pressed = index else if (focus.pressed == index) focus.pressed = null
    }
    LaunchedEffect(active) { focus.active[index] = active }

    val focused = focus.focused
    val springSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 520f)
    val width by animateDpAsState(
        when (focused) {
            null -> HeaderButtonSize
            index -> HeaderButtonSize + 14.dp
            else -> HeaderButtonSize - 6.dp
        },
        spring(dampingRatio = 0.6f, stiffness = 520f), label = "toolbarWidth",
    )
    val iconScale by animateFloatAsState(
        when (focused) {
            null -> 1f
            index -> 1.15f
            else -> 0.82f
        },
        springSpec, label = "toolbarIcon",
    )
    val halo by animateColorAsState(
        when {
            pressed -> EighthTheme.GlassPressed
            active -> EighthTheme.accent(aura).copy(alpha = 0.2f)
            focused == index -> Color.White.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        tween(160), label = "toolbarHalo",
    )
    val tint by animateColorAsState(
        when {
            !enabled -> EighthTheme.TextTertiary
            active -> EighthTheme.accent(aura)
            else -> EighthTheme.TextPrimary.copy(alpha = 0.9f)
        },
        tween(220), label = "toolbarTint",
    )
    Box(
        Modifier
            .width(width)
            .height(HeaderButtonSize)
            .clip(CircleShape)
            .background(halo)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                focus.pulse = index
                scope.launch {
                    delay(TOOLBAR_PULSE_MS)
                    if (focus.pulse == index) focus.pulse = null
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            },
        ) {
            if (content != null) {
                content()
            } else if (icon != null) {
                Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(HeaderIconSize))
            }
        }
    }
}

private const val TOOLBAR_PULSE_MS = 420L

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
    gridColumns: Int?,
    onSetGridColumns: (Int?) -> Unit,
) {
    val aura = animatedAura()
    var open by remember { mutableStateOf(false) }
    var showColumnsDialog by remember { mutableStateOf(false) }
    Box {
        ToolbarButton(
            index = 2,
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = "Menú",
            active = open,
            onClick = { open = true },
            content = if (isSyncing) {
                {
                    CircularProgressIndicator(
                        progress = { if (syncTotal > 0) (syncDone.toFloat() / syncTotal).coerceIn(0f, 1f) else 0f },
                        strokeWidth = 2.dp,
                        color = aura,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                null
            },
        )
        GlassDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            GlassMenuItem(
                label = "Carpeta de música",
                icon = Icons.Rounded.FolderOpen,
                onClick = {
                    open = false
                    onPickFolder()
                },
            )
            GlassMenuItem(
                label = "Sincronizar letras",
                icon = Icons.Rounded.Lyrics,
                enabled = hasSongs,
                onClick = {
                    open = false
                    onSyncLyrics()
                },
            )
            if (hasSongs && currentTab == ViewTab.ALBUMS) {
                GlassMenuItem(
                    label = if (isReorderingAlbums) "Guardar orden" else "Ordenar álbumes",
                    icon = if (isReorderingAlbums) Icons.Rounded.Check else Icons.Rounded.SwapVert,
                    onClick = {
                        open = false
                        onToggleReorder()
                    },
                )
            }
            if (hasSongs) {
                // Un solo punto: abre el mini-menú centrado para elegir.
                GlassMenuItem(
                    label = "Columnas del grid",
                    icon = Icons.Rounded.GridView,
                    onClick = {
                        open = false
                        showColumnsDialog = true
                    },
                )
            }
            GlassMenuItem(
                label = "Información",
                icon = Icons.Rounded.Info,
                onClick = {
                    open = false
                    onShowInfo()
                },
            )
        }
        if (showColumnsDialog) {
            GridColumnsDialog(
                current = gridColumns,
                onSelect = onSetGridColumns,
                onDismiss = { showColumnsDialog = false },
            )
        }
    }
}
