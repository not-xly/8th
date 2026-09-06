package com.xly.u_8th.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xly.u_8th.ui.theme.EighthTheme

/** Top-level screens. Sheets/dialogs are state within MainScreen. */
sealed interface AppScreen {
    data object Main : AppScreen
    data object FullPlayer : AppScreen
    data object Lyrics : AppScreen
}

/** Tiny custom backstack with animated transitions. */
class NavController {
    val stack = mutableStateListOf<AppScreen>(AppScreen.Main)

    val current: AppScreen get() = stack.last()

    fun push(screen: AppScreen) {
        if (stack.last() != screen) stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

private fun transitionFor(target: AppScreen): AnimatedContentTransitionScope<AppScreen>.() -> androidx.compose.animation.ContentTransform = {
    when (target) {
        AppScreen.FullPlayer ->
            (fadeIn(tween(200, easing = EighthTheme.EasingOutCubic)) +
                scaleIn(initialScale = 0.97f, animationSpec = tween(200, easing = EighthTheme.EasingOutCubic)))
                .togetherWith(fadeOut(tween(160)))
        AppScreen.Lyrics ->
            fadeIn(tween(240, easing = EighthTheme.EasingStandard))
                .togetherWith(fadeOut(tween(200)))
        AppScreen.Main ->
            (fadeIn(tween(160)) + scaleIn(initialScale = 1f, animationSpec = tween(160)))
                .togetherWith(fadeOut(tween(160)))
    }
}

@Composable
fun AppNavHost(nav: NavController, content: @Composable (AppScreen) -> Unit) {
    AnimatedContent(
        targetState = nav.current,
        transitionSpec = transitionFor(nav.current),
        label = "appNav",
        contentKey = { it },
    ) { screen ->
        content(screen)
    }
}
