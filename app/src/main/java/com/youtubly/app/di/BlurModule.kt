package com.youtubly.app.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.youtubly.app.blur.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.blurDataStore by preferencesDataStore(name = "blur_prefs")

@Module
@InstallIn(SingletonComponent::class)
object BlurModule {
    @Provides @Singleton fun store(@ApplicationContext c: Context) = BlurPreferencesStore(c.blurDataStore)
    @Provides @Singleton fun detector() = FaceDetector()
    @Provides @Singleton fun personSegmenter() = com.youtubly.app.blur.PersonSegmenter()
    @Provides @Singleton fun classifier(@ApplicationContext c: Context) = GenderClassifier(c)

    /**
     * HF redirects file requests to a CDN, so follow redirects and allow a generous read timeout.
     * Built from the shared client (same pool/dispatcher) with the logging interceptor stripped,
     * so the download URL and body never reach logcat.
     */
    @Provides @Singleton fun modelDownloader(@ApplicationContext c: Context, base: OkHttpClient, store: BlurPreferencesStore): ModelDownloader {
        val client = base.newBuilder()
            .apply { interceptors().clear() }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        return ModelDownloader(c, client, store)
    }

    @Provides @Singleton fun blurDiskCache(@ApplicationContext c: Context) = BlurDiskCache(c)
    @Provides @Singleton fun engine(d: FaceDetector, g: GenderClassifier, s: BlurPreferencesStore, cache: BlurDiskCache, people: com.youtubly.app.blur.PersonSegmenter) =
        BlurEngine(d, g, s, CoroutineScope(SupervisorJob() + Dispatchers.Default), cache, people)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BlurBindings {
    @Binds abstract fun subscriptions(impl: AccountSubscriptionsProvider): SubscriptionsProvider
}
