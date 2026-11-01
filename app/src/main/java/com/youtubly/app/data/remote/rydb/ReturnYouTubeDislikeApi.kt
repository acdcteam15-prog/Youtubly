package com.youtubly.app.data.remote.rydb

import retrofit2.http.GET
import retrofit2.http.Query

interface ReturnYouTubeDislikeApi {
    @GET("votes")
    suspend fun getVotes(@Query("videoId") videoId: String): DislikeResponseDto
}
