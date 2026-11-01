package com.youtubly.app.domain.repository

import com.youtubly.app.domain.model.Video

interface SearchRepository {
    suspend fun search(query: String): Result<List<Video>>
    suspend fun suggestions(query: String): Result<List<String>>
}
