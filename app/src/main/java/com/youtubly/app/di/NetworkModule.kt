package com.youtubly.app.di

import com.youtubly.app.core.Constants
import com.youtubly.app.data.remote.rydb.ReturnYouTubeDislikeApi
import com.youtubly.app.data.remote.sponsorblock.SponsorBlockApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Separate from the API client above — thumbnail requests are high-volume and don't need
     *  request/response logging, and shouldn't compete with API calls over the same connection
     *  pool sizing (which is tuned for occasional JSON requests, not a scrolling image feed). */
    @Provides @Singleton @Named("images")
    fun provideImageOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton @Named("sponsorBlock")
    fun provideSponsorBlockRetrofit(client: OkHttpClient, json: Json): Retrofit =
        retrofit(Constants.SPONSORBLOCK_BASE_URL, client, json)

    @Provides @Singleton @Named("rydb")
    fun provideRydbRetrofit(client: OkHttpClient, json: Json): Retrofit =
        retrofit(Constants.RETURN_YOUTUBE_DISLIKE_BASE_URL, client, json)


    @Provides @Singleton
    fun provideSponsorBlockApi(@Named("sponsorBlock") r: Retrofit): SponsorBlockApi = r.create(SponsorBlockApi::class.java)

    @Provides @Singleton
    fun provideRydbApi(@Named("rydb") r: Retrofit): ReturnYouTubeDislikeApi = r.create(ReturnYouTubeDislikeApi::class.java)

}
