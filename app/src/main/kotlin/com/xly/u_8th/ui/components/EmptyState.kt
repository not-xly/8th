package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.ui.theme.EighthTheme

/** Sin biblioteca: círculos concéntricos + botón de carpeta. */
@Composable
fun EmptyState(
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .shadow(24.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.28f), ambientColor = Color.Transparent)
                    .background(EighthTheme.GlassSurface.copy(alpha = 0.64f), CircleShape)
                    .border(BorderStroke(0.9.dp, EighthTheme.GlassBorder), CircleShape)
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .background(EighthTheme.Primary.copy(alpha = 0.12f), CircleShape)
                        .border(BorderStroke(0.8.dp, EighthTheme.Primary.copy(alpha = 0.16f)), CircleShape)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Album,
                        null,
                        tint = EighthTheme.Primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "No hay álbumes cargados",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                color = EighthTheme.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Selecciona una carpeta o archivos de audio para cargar tu música.",
                fontSize = 14.sp,
                color = EighthTheme.TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 19.6.sp,
            )
            Spacer(Modifier.height(24.dp))
            PillButton(
                "Seleccionar carpeta",
                onPickFolder,
                icon = Icons.Rounded.FolderOpen,
                primary = true,
            )
        }
    }
}
