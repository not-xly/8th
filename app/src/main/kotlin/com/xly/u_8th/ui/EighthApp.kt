package com.xly.u_8th.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.xly.u_8th.ui.nav.AppNavHost
import com.xly.u_8th.ui.nav.AppScreen
import com.xly.u_8th.ui.nav.NavController
import com.xly.u_8th.ui.screens.fullplayer.FullPlayerScreen
import com.xly.u_8th.ui.screens.lyrics.LyricsScreen
import com.xly.u_8th.ui.screens.main.MainScreen
import com.xly.u_8th.ui.theme.EighthTheme

/**
 * Raíz de la app: fondo degradado + nav stack (Main / FullPlayer / Lyrics)
 * con BackHandler para desapilar.
 */
@Composable
fun EighthApp() {
    val nav = remember { NavController() }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(EighthTheme.BackgroundGradientTop, EighthTheme.BackgroundGradientBottom),
                ),
            ),
    ) {
        AppNavHost(nav) { screen ->
            when (screen) {
                AppScreen.Main -> MainScreen(nav)
                AppScreen.FullPlayer -> FullPlayerScreen(nav)
                AppScreen.Lyrics -> LyricsScreen(nav)
            }
        }
    }
}
