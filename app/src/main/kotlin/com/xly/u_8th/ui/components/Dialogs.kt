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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/** Diálogo de información con stats y limpieza de caché. */
@Composable
fun InfoDialog(
    songCount: Int,
    albumCount: Int,
    artistCount: Int,
    onClearCache: (onResult: (Int) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onShowNotification: (count: Int) -> Unit,
) {
    val aura = animatedAura()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true),
    ) {
        Column(
            Modifier
                .glassPopup()
                .padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(aura.copy(alpha = 0.14f))
                        .border(BorderStroke(0.8.dp, aura.copy(alpha = 0.16f)), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = aura, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "8th",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    color = EighthTheme.TextPrimary,
                )
            }
            Spacer(Modifier.height(20.dp))
            StatLine("Artistas", artistCount, aura)
            StatLine("Álbumes", albumCount, aura)
            StatLine("Canciones", songCount, aura)
            Spacer(Modifier.height(14.dp))
            // Explica QUÉ borra exactamente (antes era un botón misterioso).
            Text(
                "«Limpiar caché» borra las portadas guardadas en disco; se vuelven a generar solas al navegar. No borra canciones ni letras.",
                fontSize = 11.5.sp,
                color = EighthTheme.TextTertiary,
                lineHeight = 15.5.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(
                    "Limpiar caché",
                    onClick = {
                        onClearCache { n ->
                            onDismiss()
                            onShowNotification(n)
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                PillButton("Entendido", onDismiss, primary = true)
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: Int, aura: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(aura.copy(alpha = 0.85f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = EighthTheme.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$value",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = EighthTheme.TextPrimary,
        )
    }
}

/** Opciones del grid: etiqueta -> columnas (null = "Pantalla"/auto). */
private val GRID_COLUMN_OPTIONS: List<Pair<String, Int?>> = listOf(
    "Pantalla (auto)" to null,
    "5 columnas" to 5,
    "6 columnas" to 6,
    "7 columnas" to 7,
)

/**
 * Mini-menú centrado para elegir las columnas del grid de álbumes
 * ("Pantalla" = auto, o fijas 5/6/7). La opción activa lleva check.
 */
@Composable
fun GridColumnsDialog(
    current: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val aura = animatedAura()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Scrim manual: tocar fuera cierra; tocar dentro no.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 48.dp)
                    .fillMaxWidth()
                    .glassPopup()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume el toque dentro de la tarjeta */ }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    "Columnas del grid",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                    color = EighthTheme.TextTertiary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
                for ((label, value) in GRID_COLUMN_OPTIONS) {
                    val selected = current == value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                            if (selected) {
                                Icon(Icons.Rounded.Check, null, tint = aura, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) EighthTheme.TextPrimary else EighthTheme.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}
