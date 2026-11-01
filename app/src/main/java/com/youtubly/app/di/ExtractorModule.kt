package com.youtubly.app.di

import com.youtubly.app.data.remote.newpipe.NewPipeDownloaderImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExtractorModule {
    @Provides @Singleton
    fun provideYoutubeService(okHttpClient: OkHttpClient): StreamingService {
        NewPipe.init(NewPipeDownloaderImpl(okHttpClient))
        return ServiceList.YouTube
    }
}
