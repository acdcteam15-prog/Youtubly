package com.youtubly.app.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.youtubly.app.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore by preferencesDataStore(name = Constants.AUTH_TOKEN_DATASTORE_NAME)

/**
 * Holds the youtube.com web session cookies captured after sign-in.
 * These are equivalent to a logged-in browser — kept in app-private storage,
 * excluded from backups (android:allowBackup="false").
 */
@Singleton
class AuthTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyCookies = stringPreferencesKey("yt_session_cookies")

    val cookies: Flow<String?> = context.authDataStore.data.map { it[keyCookies] }

    suspend fun current(): String? = cookies.first()

    suspend fun save(cookieHeader: String) {
        context.authDataStore.edit { it[keyCookies] = cookieHeader }
    }

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }
}
