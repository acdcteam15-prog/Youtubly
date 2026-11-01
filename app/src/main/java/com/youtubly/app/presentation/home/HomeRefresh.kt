package com.youtubly.app.presentation.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Counts Home-tab taps (from another tab or while already on Home). A counter rather than a
 * one-shot event: when the tap comes from another tab, HomeScreen isn't composed yet, so it
 * compares against the last count it handled (kept in saved state) and reloads on return.
 */
object HomeRefresh {
    private val _taps = MutableStateFlow(0)
    val taps: StateFlow<Int> = _taps
    fun request() { _taps.value++ }
}
