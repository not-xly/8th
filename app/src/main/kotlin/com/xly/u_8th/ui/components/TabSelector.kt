package com.xly.u_8th.ui.components

import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.domain.ViewTab
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/**
 * Control segmentado Listas/Álbumes/Cola. El indicador sigue al pager en
 * vivo ([pagerPosition] = página + fracción), así acompaña al dedo al
 * deslizar en vez de esperar a que la página se asiente.
 */
@Composable
fun TabSelector(
    pagerPosition: () -> Float,
    onTabSelected: (ViewTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = animatedAura()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .glass()
            .padding(3.dp),
    ) {
        val slot = maxWidth / ViewTab.entries.size
        Box(
            Modifier
                .matchParentSize()
                .padding(end = maxWidth - slot)
                .offset { IntOffset((slot.toPx() * pagerPosition()).roundToInt(), 0) }
                .glass(accent = EighthTheme.accent(aura)),
        )
        Row(Modifier.fillMaxWidth()) {
            for (tab in ViewTab.entries) {
                TabPill(tab, pagerPosition, Modifier.weight(1f)) { onTabSelected(tab) }
            }
        }
    }
}

@Composable
private fun TabPill(
    tab: ViewTab,
    pagerPosition: () -> Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 1 cuando el indicador está encima, 0 a una pestaña de distancia.
    val amount by remember(tab) {
        derivedStateOf { (1f - abs(pagerPosition() - tab.ordinal)).coerceIn(0f, 1f) }
    }
    val fg = lerp(EighthTheme.TextSecondary, Color.White, amount)
    Box(
        modifier
            .selectable(
                selected = amount > 0.5f,
                interactionSource = remember(tab) { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (tab) {
                    ViewTab.PLAYLISTS -> Icons.AutoMirrored.Rounded.PlaylistPlay
                    ViewTab.ALBUMS -> Icons.Rounded.Album
                    ViewTab.QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
                },
                null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when (tab) {
                    ViewTab.PLAYLISTS -> "Listas"
                    ViewTab.ALBUMS -> "Álbumes"
                    ViewTab.QUEUE -> "Cola"
                },
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
