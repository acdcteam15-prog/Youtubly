package com.youtubly.app.blur

import androidx.compose.runtime.staticCompositionLocalOf

/** Provided once in MainActivity: CompositionLocalProvider(LocalBlurEngine provides engine) { ... } */
val LocalBlurEngine = staticCompositionLocalOf<BlurEngine> { error("LocalBlurEngine not provided") }
