package com.youtubly.app.domain.model

sealed interface AuthState {
    data object SignedOut : AuthState
    data object SignedIn : AuthState
}
