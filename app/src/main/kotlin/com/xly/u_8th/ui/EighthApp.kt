package com.xly.u_8th.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.xly.u_8th.ui.theme.AuraProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.xly.u_8th.ui.nav.AppNavHost
import com.xly.u_8th.ui.nav.AppScreen
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.screens.fullplayer.FullPlayerScreen
import com.xly.u_8th.ui.screens.lyrics.LyricsScreen
import com.xly.u_8th.ui.screens.main.MainScreen
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura

/**
 * App root: gradient background + nav stack (Main / FullPlayer / Lyrics)
 * with a BackHandler to pop. Everything sits inside a MaterialTheme with
 * the app's dark palette so material3 components (menus, sheets, dialogs)
 * render light text on the dark glass surfaces.
 */
@Composable
fun EighthApp() = AuraProvider {
    val nav = rememberSaveable(saver = NavController.Saver) { NavController() }
    val aura = animatedAura()
    // `remember(aura)`: el aura se anima ~440ms en cada cambio de canción.
    // Construir el ColorScheme en cada frame producía un objeto nuevo, nunca
    // equals al anterior, e invalidaba TODO el subárbol (grid, mini player,
    // pantallas) durante toda la animación.
    val scheme = remember(aura) {
        darkColorScheme(
            primary = aura,
            onPrimary = Color.White,
            secondary = EighthTheme.PrimarySoft,
            onSecondary = EighthTheme.TextPrimary,
            background = EighthTheme.Background,
            onBackground = EighthTheme.TextPrimary,
            surface = EighthTheme.GlassSurface,
            onSurface = EighthTheme.TextPrimary,
            onSurfaceVariant = EighthTheme.TextSecondary,
            outline = EighthTheme.TextTertiary,
        )
    }
    val backdrop = remember {
        Brush.verticalGradient(
            listOf(EighthTheme.BackgroundGradientTop, EighthTheme.BackgroundGradientBottom),
        )
    }
    MaterialTheme(colorScheme = scheme) {
        BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
        Box(Modifier.fillMaxSize().background(backdrop)) {
            AppNavHost(nav) { screen ->
                when (screen) {
                    AppScreen.Main -> MainScreen(nav)
                    AppScreen.FullPlayer -> FullPlayerScreen(nav)
                    AppScreen.Lyrics -> LyricsScreen(nav)
                }
            }
        }
    }
}
