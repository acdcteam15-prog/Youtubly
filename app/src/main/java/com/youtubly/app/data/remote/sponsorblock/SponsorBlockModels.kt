package com.youtubly.app.data.remote.sponsorblock

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SponsorSegmentDto(
    val category: String,
    val segment: List<Double>,
    @SerialName("UUID") val uuid: String? = null
)

/** DeArrow crowd-sourced branding: https://wiki.sponsor.ajay.app/w/API_Docs/DeArrow */
@Serializable
data class BrandingDto(
    val titles: List<BrandingTitleDto> = emptyList(),
    val thumbnails: List<BrandingThumbDto> = emptyList()
)

@Serializable
data class BrandingTitleDto(
    val title: String,
    val original: Boolean = false,
    val votes: Int = 0,
    val locked: Boolean = false
)

@Serializable
data class BrandingThumbDto(
    val timestamp: Double? = null,
    val original: Boolean = false,
    val votes: Int = 0,
    val locked: Boolean = false
)
