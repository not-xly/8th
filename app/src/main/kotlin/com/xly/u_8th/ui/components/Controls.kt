package com.xly.u_8th.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.xly.u_8th.ui.theme.EighthTheme

/** Icono play/pausa que cambia con un pequeño rebote (escala + fundido). */
@Composable
fun AnimatedPlayPauseIcon(playing: Boolean, tint: Color, size: Dp) {
    AnimatedContent(
        targetState = playing,
        transitionSpec = {
            (scaleIn(initialScale = 0.5f, animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) +
                fadeIn(tween(120)))
                .togetherWith(scaleOut(targetScale = 0.5f, animationSpec = tween(120)) + fadeOut(tween(100)))
        },
        label = "playPauseIcon",
    ) { isPlaying ->
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pausa" else "Reproducir",
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

/** Píldora de cristal con icono y texto (acciones secundarias). */
@Composable
fun GlassPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val content = EighthTheme.TextPrimary.copy(alpha = if (enabled) 0.88f else 0.35f)
    Row(
        modifier
            .pressClickable(enabled = enabled, pressedScale = 0.94f, onClick = onClick)
            .glass()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = content,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}
