package com.youtubly.app.domain.repository

import com.youtubly.app.domain.model.AuthState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>
    /** Called by the sign-in WebView once youtube.com session cookies exist. */
    suspend fun saveSession(cookieHeader: String)
    suspend fun signOut()
}
