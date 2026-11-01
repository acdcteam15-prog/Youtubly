package com.youtubly.app.presentation.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Downloads : Screen("downloads")
    /** Video editor tab. Videos arrive through EditorInbox, not a route argument. */
    data object Editor : Screen("edit")
    data object Library : Screen("library")
    data object SignIn : Screen("signin")
    data object History : Screen("history")
    data object Hidden : Screen("hidden")
    data object Playlists : Screen("playlists")
    data object Subscriptions : Screen("subscriptions")
    data object AudioLibrary : Screen("audio")
    data object Settings : Screen("settings")
    data object MusicRemover : Screen("settings/music")
    data object MusicExcluded : Screen("settings/music/excluded")
    data object BlurSettings : Screen("settings/blur")
    data object BlurExcluded : Screen("settings/blur/excluded")
    data object Diagnostics : Screen("settings/diagnostics")
    data object Storage : Screen("settings/storage")
    data object Backup : Screen("settings/backup")
    data object Updates : Screen("settings/updates")
    data object ChannelGroups : Screen("groups")
    data object ChannelGroup : Screen("group/{name}") {
        fun createRoute(name: String): String = "group/${Uri.encode(name)}"
    }
    data object SponsorBlock : Screen("settings/sponsorblock")

    data object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(id: Long): String = "playlist/$id"
    }

    // Phase 7: streamed videos no longer have a route — they open in the player sheet
    // (presentation/player/PlayerSheet.kt) via PlayerSheetState.open(url, startMs).

    /** Plays a file already saved on-device, from the Downloads tab. */
    data object LocalPlayer : Screen("local_player/{downloadId}") {
        fun createRoute(downloadId: String): String = "local_player/${Uri.encode(downloadId)}"
    }

    /** A YouTube playlist opened from a channel's Playlists tab. */
    data object YoutubePlaylist : Screen("ytplaylist/{playlistUrl}") {
        fun createRoute(playlistUrl: String): String = "ytplaylist/${Uri.encode(playlistUrl)}"
    }

    data object Channel : Screen("channel/{channelUrl}") {
        fun createRoute(channelUrl: String): String = "channel/${Uri.encode(channelUrl)}"
    }
}
