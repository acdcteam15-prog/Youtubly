package com.youtubly.app.core

object Constants {
    const val SPONSORBLOCK_BASE_URL = "https://sponsor.ajay.app/"
    const val RETURN_YOUTUBE_DISLIKE_BASE_URL = "https://returnyoutubedislikeapi.com/"
    const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "youtubly_downloads"
    const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "youtubly_playback"
    const val EDITOR_NOTIFICATION_CHANNEL_ID = "youtubly_editor"
    /** Edited clips are saved to the public Download/Youtubly folder. */
    const val EDITOR_OUTPUT_RELATIVE_DIR = "Youtubly"
    const val DOWNLOAD_DIR_NAME = "Youtubly"
    const val DATASTORE_PREFS_NAME = "youtubly_prefs"
    const val AUTH_TOKEN_DATASTORE_NAME = "youtubly_auth"

    // ---- Gender model (downloaded from Hugging Face on first "Import model") ----
    // Optional built-in default. Normally leave the placeholder and type your repo in the app
    // (Content blur -> Model source) instead, so no rebuild is needed. The URL is built as
    //   https://huggingface.co/<REPO>/resolve/main/<FILE>
    const val GENDER_MODEL_REPO = ""
    const val GENDER_MODEL_FILE = ""
    const val GENDER_MODEL_REVISION = "main"
    /** Optional. Lowercase hex SHA-256 of the file; empty string skips the integrity check. */
    const val GENDER_MODEL_SHA256 = ""
}
