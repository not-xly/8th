package com.xly.u_8th.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/**
 * Barra de búsqueda. El hint es
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
            .glass()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, null, tint = EighthTheme.TextSecondary, modifier = Modifier.size(20.dp))
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
    GlassIconButton(icon = icon, contentDescription = description, onClick = onTap)
}
