package com.youtubly.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Incognito session: while on, nothing you watch or search is written to history, resume
 * positions or search suggestions. Deliberately **not persisted** — restarting the app always
 * turns it off, so it can't be left on by accident.
 */
object Incognito {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    val isOn: Boolean get() = _on.value
    fun set(enabled: Boolean) { _on.value = enabled }
}
