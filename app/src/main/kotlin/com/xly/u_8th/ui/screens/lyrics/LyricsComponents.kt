package com.xly.u_8th.ui.screens.lyrics

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import com.xly.u_8th.ui.components.GlassIconButton
import com.xly.u_8th.ui.components.glassSurface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.data.LrcLine
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

@Composable
internal fun PlainLyrics(lines: List<LrcLine>) {
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdges()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
    ) {
        Text(
            lines.joinToString("\n") { it.text },
            color = EighthTheme.TextPrimary.copy(alpha = 0.82f),
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 29.sp,
            letterSpacing = (-0.2).sp,
        )
    }
}

/** Desvanece arriba y abajo el contenido (máscara real, vale sobre cualquier fondo). */
internal fun Modifier.fadingEdges(fraction: Float = 0.08f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                fraction to Color.Black,
                1f - fraction to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Estado vacío/carga centrado: icono (o spinner), título, texto y acciones. */
@Composable
internal fun BoxScope.LyricsStatus(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = EighthTheme.TextTertiary, modifier = Modifier.size(44.dp))
        } else {
            CircularProgressIndicator(
                color = animatedAura(),
                trackColor = Color.White.copy(alpha = 0.08f),
                strokeWidth = 3.dp,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = EighthTheme.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = EighthTheme.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        if (actions != null) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Ajuste del desfase: −/+ 100 ms, valor y restablecer, en una píldora. */
@Composable
internal fun OffsetControls(offsetMs: Long, enabled: Boolean, onChange: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .glassSurface(radius = 22.dp, opacity = 0.7f)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            Icons.Rounded.Remove, "Adelantar 100 ms",
            onClick = { onChange(offsetMs - 100) },
            enabled = enabled && offsetMs > -60_000,
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                (if (offsetMs > 0) "+" else "") + "$offsetMs ms",
                color = EighthTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Text(
                when {
                    offsetMs > 0 -> "letra retrasada"
                    offsetMs < 0 -> "letra adelantada"
                    else -> "sin desfase"
                },
                color = EighthTheme.TextTertiary,
                fontSize = 11.sp,
            )
        }
        GlassIconButton(
            Icons.Rounded.Add, "Retrasar 100 ms",
            onClick = { onChange(offsetMs + 100) },
            enabled = enabled && offsetMs < 60_000,
        )
        Spacer(Modifier.width(6.dp))
        GlassIconButton(
            Icons.Rounded.RestartAlt, "Restablecer desfase",
            onClick = { onChange(0) },
            enabled = enabled && offsetMs != 0L,
        )
    }
}
