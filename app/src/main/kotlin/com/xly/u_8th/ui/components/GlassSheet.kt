package com.xly.u_8th.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xly.u_8th.ui.theme.EighthTheme

/**
 * Hoja modal con el estilo de la app (cristal oscuro, tirador propio, sin
 * estado parcial). El alto va en el CONTENIDO ([heightFraction]) y no en
 * el modifier del sheet: ahí el anclaje de la animación se calcula mal y
 * la hoja se pinta arriba en vez de entrar desde abajo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismiss: () -> Unit,
    heightFraction: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fullScreen = heightFraction >= 1f
    val flingGuard = remember { FlingToSheetGuard() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // A pantalla completa, opaca: con transparencia se intuía la cabecera
        // de detrás. Las hojas parciales flotan como un panel propio.
        containerColor = if (fullScreen) EighthTheme.GlassSurface else Color.Transparent,
        contentColor = EighthTheme.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        shape = RoundedCornerShape(topStart = EighthTheme.RadiusL, topEnd = EighthTheme.RadiusL),
        tonalElevation = 0.dp,
        // A pantalla completa la hoja llega hasta arriba: el tirador baja
        // bajo la barra de estado / notch para no quedar tapado.
        dragHandle = if (fullScreen) {
            { Box(Modifier.windowInsetsPadding(TopSafeInsets)) { SheetHandle() } }
        } else {
            null
        },
    ) {
        if (fullScreen) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .nestedScroll(flingGuard)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = content,
            )
        } else {
            Column(
                Modifier
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .fillMaxHeight(heightFraction)
                    .glassPopup(RoundedCornerShape(EighthTheme.RadiusXL + 6.dp))
                    .nestedScroll(flingGuard)
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            ) {
                SheetHandle(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
                content()
            }
        }
    }
}

/**
 * Reparto del gesto entre la lista y la hoja de Material, como en iOS: un
 * gesto que empezó desplazando la lista no mueve la hoja aunque la lista
 * llegue arriba a mitad de camino, y el impulso sobrante de un fling
 * tampoco le llega. Antes, al subir rápido por un álbum largo, el resto
 * del gesto arrastraba la hoja hacia abajo y la cerraba. Con la lista ya
 * arriba, tirar hacia abajo sigue cerrándola.
 */
private class FlingToSheetGuard : NestedScrollConnection {
    private var listScrolled = false
    private var draggingSheet = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (consumed.y != 0f && !draggingSheet) listScrolled = true
        if (listScrolled) return available
        if (available.y > 0f) draggingSheet = true
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // Fin del gesto: la hoja solo recibe impulso si era ella la arrastrada.
        val pass = draggingSheet
        listScrolled = false
        draggingSheet = false
        return if (pass) Velocity.Zero else available
    }
}

@Composable
private fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 8.dp)
            .width(36.dp)
            .height(4.dp)
            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)),
    )
}

/** Barra de estado + recorte de pantalla (notch), solo el lado superior. */
private val TopSafeInsets: WindowInsets
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)

/** Icono sobre un cuadrado tintado (cabeceras de hojas). */
@Composable
fun TintedIconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(36.dp)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .border(BorderStroke(0.8.dp, tint.copy(alpha = 0.16f)), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Separador fino bajo la cabecera de una hoja. */
@Composable
fun SheetDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.7.dp)
            .background(EighthTheme.GlassBorder),
    )
}
