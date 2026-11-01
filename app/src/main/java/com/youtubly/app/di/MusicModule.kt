package com.youtubly.app.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.youtubly.app.music.MusicPreferencesStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.musicDataStore by preferencesDataStore(name = "music_prefs")

@Module
@InstallIn(SingletonComponent::class)
object MusicModule {
    @Provides @Singleton fun musicStore(@ApplicationContext c: Context) = MusicPreferencesStore(c.musicDataStore)
}
