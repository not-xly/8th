package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/**
 * Barra de búsqueda (port of widgets/search_bar_widget.dart). El hint es
 * "Buscar canciones, artistas o álbumes" según la spec nativa.
 */
@Composable
fun SearchBarWidget(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = animatedAura()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(16.dp, RoundedCornerShape(EighthTheme.RadiusL), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f), ambientColor = androidx.compose.ui.graphics.Color.Transparent)
            .background(EighthTheme.GlassSurface.copy(alpha = 0.52f), RoundedCornerShape(EighthTheme.RadiusL))
            .border(
                BorderStroke(0.8.dp, aura.copy(alpha = 0.18f)),
                RoundedCornerShape(EighthTheme.RadiusL),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(aura.copy(alpha = 0.14f))
                .border(BorderStroke(0.7.dp, aura.copy(alpha = 0.16f)), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Search, null, tint = aura, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChanged,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = EighthTheme.TextPrimary,
            ),
            cursorBrush = SolidColor(aura),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(vertical = 6.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            "Buscar canciones, artistas o álbumes",
                            fontSize = 14.sp,
                            color = EighthTheme.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            SearchIconBox(Icons.Rounded.Close, "Limpiar", onClear)
        }
        Spacer(Modifier.width(8.dp))
        // Unfocus antes de sacar la barra: si no, el teclado quedaba abierto.
        SearchIconBox(Icons.AutoMirrored.Rounded.ArrowBack, "Cerrar búsqueda") {
            focusManager.clearFocus()
            onClose()
        }
    }
}

/** Caja de icono 34x34 idéntica a la lupa (sin tap-target de IconButton). */
@Composable
private fun SearchIconBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f))
            .border(BorderStroke(0.7.dp, EighthTheme.GlassBorder), RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = EighthTheme.TextSecondary, modifier = Modifier.size(18.dp))
    }
}
