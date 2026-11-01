package com.youtubly.app.di

import com.youtubly.app.data.repository.AccountRepositoryImpl
import com.youtubly.app.data.repository.AuthRepositoryImpl
import com.youtubly.app.data.repository.ChannelRepositoryImpl
import com.youtubly.app.data.repository.DownloadRepositoryImpl
import com.youtubly.app.data.repository.SearchRepositoryImpl
import com.youtubly.app.data.repository.VideoEnrichmentRepositoryImpl
import com.youtubly.app.data.repository.VideoRepositoryImpl
import com.youtubly.app.domain.repository.AccountRepository
import com.youtubly.app.domain.repository.AuthRepository
import com.youtubly.app.domain.repository.ChannelRepository
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.domain.repository.SearchRepository
import com.youtubly.app.domain.repository.VideoEnrichmentRepository
import com.youtubly.app.domain.repository.VideoRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds @Singleton abstract fun bindVideoRepository(impl: VideoRepositoryImpl): VideoRepository
    @Binds @Singleton abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository
    @Binds @Singleton abstract fun bindChannelRepository(impl: ChannelRepositoryImpl): ChannelRepository
    @Binds @Singleton abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
    @Binds @Singleton abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
    @Binds @Singleton abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository
    @Binds @Singleton abstract fun bindVideoEnrichmentRepository(impl: VideoEnrichmentRepositoryImpl): VideoEnrichmentRepository
}
