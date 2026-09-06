package com.xly.u_8th.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.domain.ViewTab
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/** Pills Álbumes/Cola (port of widgets/tab_selector.dart). */
@Composable
fun TabSelector(
    currentTab: ViewTab,
    albumCount: Int,
    queueLength: Int,
    onTabSelected: (ViewTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = animatedAura()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(EighthTheme.GlassSurface.copy(alpha = 0.52f), RoundedCornerShape(EighthTheme.RadiusM))
            .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(EighthTheme.RadiusM))
            .padding(4.dp),
    ) {
        TabPill(ViewTab.ALBUMS, "Álbumes ($albumCount)", currentTab == ViewTab.ALBUMS, aura, Modifier.weight(1f)) { onTabSelected(ViewTab.ALBUMS) }
        TabPill(ViewTab.QUEUE, "Cola ($queueLength)", currentTab == ViewTab.QUEUE, aura, Modifier.weight(1f)) { onTabSelected(ViewTab.QUEUE) }
    }
}

@Composable
private fun TabPill(
    tab: ViewTab,
    label: String,
    active: Boolean,
    aura: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Colores siempre animando (nunca transparente<->color): el brillo
    // interpola en vez de saltar al cambiar de tab.
    val fill by animateColorAsState(
        targetValue = if (active) aura.copy(alpha = 0.94f) else Color.Transparent,
        animationSpec = tween(240),
        label = "tabFill",
    )
    val border by animateColorAsState(
        targetValue = if (active) aura.copy(alpha = 0.9f) else Color.Transparent,
        animationSpec = tween(240),
        label = "tabBorder",
    )
    val shadow by animateColorAsState(
        targetValue = if (active) aura.copy(alpha = 0.22f) else Color.Transparent,
        animationSpec = tween(240),
        label = "tabShadow",
    )
    val fg by animateColorAsState(
        targetValue = if (active) Color.White else EighthTheme.TextSecondary,
        animationSpec = tween(240),
        label = "tabFg",
    )
    Box(
        modifier
            .shadow(10.dp, RoundedCornerShape(EighthTheme.RadiusS), spotColor = shadow, ambientColor = Color.Transparent)
            .background(fill, RoundedCornerShape(EighthTheme.RadiusS))
            .border(BorderStroke(0.8.dp, border), RoundedCornerShape(EighthTheme.RadiusS))
            .clickable(
                interactionSource = remember(tab) { MutableInteractionSource() },
                indication = null,
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
                if (tab == ViewTab.ALBUMS) Icons.Rounded.Album else Icons.AutoMirrored.Rounded.QueueMusic,
                null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
            Box(Modifier.size(width = 7.dp, height = 0.dp))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.2.sp,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
