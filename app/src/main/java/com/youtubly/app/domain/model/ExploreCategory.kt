package com.youtubly.app.domain.model

/**
 * YouTube removed its single "Trending" page on July 21, 2025.
 * These are the replacement category charts exposed by NewPipe Extractor (kiosk IDs).
 */
enum class ExploreCategory(val kioskId: String, val label: String) {
    MUSIC("trending_music", "Music"),
    GAMING("trending_gaming", "Gaming"),
    MOVIES("trending_movies_and_shows", "Movies & TV"),
    PODCASTS("trending_podcasts_episodes", "Podcasts"),
    LIVE("live", "Live")
}
