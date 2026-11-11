package com.youtubly.app.presentation.components

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * One rule for the screen orientation, so nothing stale survives:
 *  - the app lives in PORTRAIT (phones), whatever the phone's rotate lock says;
 *  - the fullscreen button may turn the screen landscape, and leaving fullscreen goes back to PORTRAIT explicitly
 *    (handing control back to "the system's rotation" is what used to leave the app in landscape);
 *  - with "Rotate to fullscreen" on (or on a tablet/unfolded screen) rotation follows the phone instead.
 */
object OrientationPolicy {
    /** What the orientation request should be right now. [base] is the app's resting orientation. */
    fun desired(fullscreen: Boolean, lockLandscape: Boolean, allowRotation: Boolean, base: Int): Int = when {
        fullscreen && lockLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        allowRotation -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else -> base
    }

    /** Phones rest in portrait; tablets and big screens may rotate freely. */
    fun baseFor(smallestWidthDp: Int): Int =
        if (smallestWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

/** What the app wants right now. MainActivity re-applies it every time it starts or resumes. */
object OrientationState {
    @Volatile var base: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    @Volatile var desired: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    fun init(activity: Activity) {
        base = OrientationPolicy.baseFor(activity.resources.configuration.smallestScreenWidthDp)
        desired = base
        activity.requestedOrientation = desired
    }

    fun apply(activity: Activity?, value: Int) {
        desired = value
        if (activity != null && activity.requestedOrientation != value) activity.requestedOrientation = value
    }
}

/**
 * Applies the orientation for a player screen and puts the app back to its resting orientation when the screen goes
 * away, however it goes (back, swipe down, picture-in-picture, the app being closed).
 */
@Composable
fun OrientationEffect(fullscreen: Boolean, lockLandscape: Boolean, allowRotation: Boolean) {
    val activity = LocalView.current.context as? Activity
    val want = OrientationPolicy.desired(fullscreen, lockLandscape, allowRotation, OrientationState.base)
    DisposableEffect(activity, want) {
        OrientationState.apply(activity, want)
        onDispose { }
    }
    DisposableEffect(activity) {
        onDispose { OrientationState.apply(activity, OrientationState.base) }
    }
}
