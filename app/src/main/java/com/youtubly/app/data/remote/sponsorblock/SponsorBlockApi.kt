package com.youtubly.app.data.remote.sponsorblock

import retrofit2.http.GET
import retrofit2.http.Query

interface SponsorBlockApi {
    @GET("api/skipSegments")
    suspend fun getSegments(
        @Query("videoID") videoId: String,
        @Query("categories") categories: String =
            "[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"preview\",\"music_offtopic\",\"filler\"]"
    ): List<SponsorSegmentDto>

    @GET("api/branding")
    suspend fun getBranding(@Query("videoID") videoId: String): BrandingDto
}
