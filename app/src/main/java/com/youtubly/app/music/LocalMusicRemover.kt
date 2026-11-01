package com.youtubly.app.music

import androidx.compose.runtime.staticCompositionLocalOf

/** Provided once in MainActivity (like LocalBlurEngine) so any screen can reach the music remover's settings. */
val LocalMusicRemover = staticCompositionLocalOf<MusicRemover> { error("LocalMusicRemover not provided") }
