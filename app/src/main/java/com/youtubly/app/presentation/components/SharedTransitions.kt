package com.youtubly.app.presentation.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Provided by YoutublyNavHost around the NavHost. Null in previews / outside the nav graph. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The current navigation destination's AnimatedContentScope. */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Card → player thumbnail. Both ends use key "thumb-<video url>"; when either scope is
 * missing (mini player, previews) this is a no-op and the normal nav transition runs.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedThumbnail(videoUrl: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedThumbnail.sharedElement(
            state = rememberSharedContentState(key = "thumb-$videoUrl"),
            animatedVisibilityScope = animated
        )
    }
}

/**
 * The exact thumbnail URL a card was showing when tapped (DeArrow / data-saver variants
 * included), so the player can draw the same, already-cached image on the first frame of
 * the transition instead of popping it in later.
 */
object SharedThumbs {
    private val map = ConcurrentHashMap<String, String>()
    fun put(videoUrl: String, thumbnailUrl: String?) { if (thumbnailUrl != null) map[videoUrl] = thumbnailUrl }
    fun get(videoUrl: String): String? = map[videoUrl]
}
