package com.youtubly.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Forces light (white) status-bar icons while in composition; restores on leave. */
@Composable
fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
}


/** Hidden system bars while [fullscreen] (the orientation is handled by OrientationEffect); shows them again afterwards. */
@Composable
fun FullscreenEffect(fullscreen: Boolean, lockLandscape: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(fullscreen, lockLandscape) {
        val activity = view.context as? android.app.Activity
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
