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


/** Landscape + hidden system bars while [fullscreen]; restores portrait/bars afterwards. */
@Composable
fun FullscreenEffect(fullscreen: Boolean, lockLandscape: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(fullscreen, lockLandscape) {
        val activity = view.context as? android.app.Activity
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            // Not locked when the user got here by rotating (Phase 8 "Rotate to fullscreen"),
            // so turning the phone back upright can exit again.
            if (lockLandscape) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
