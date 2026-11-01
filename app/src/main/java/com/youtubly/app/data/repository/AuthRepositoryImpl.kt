package com.youtubly.app.data.repository

import android.webkit.CookieManager
import com.youtubly.app.data.local.datastore.AuthTokenStore
import com.youtubly.app.domain.model.AuthState
import com.youtubly.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authTokenStore: AuthTokenStore
) : AuthRepository {

    override fun observeAuthState(): Flow<AuthState> =
        authTokenStore.cookies.map { if (it.isNullOrBlank()) AuthState.SignedOut else AuthState.SignedIn }

    override suspend fun saveSession(cookieHeader: String) = authTokenStore.save(cookieHeader)

    override suspend fun signOut() {
        authTokenStore.clear()
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
