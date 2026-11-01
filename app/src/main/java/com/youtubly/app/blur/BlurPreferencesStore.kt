package com.youtubly.app.blur

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BlurPreferencesStore(internal val ds: DataStore<Preferences>) {

    private object K {
        val ENABLED = booleanPreferencesKey("enabled")
        val FEMALE = booleanPreferencesKey("blur_female")
        val MALE = booleanPreferencesKey("blur_male")
        val UNCERTAIN = booleanPreferencesKey("blur_uncertain")
        val MODE = stringPreferencesKey("mode")
        val STRENGTH = intPreferencesKey("strength")
        val PADDING = intPreferencesKey("padding")
        val MIN_FACE = intPreferencesKey("min_face")
        val CONF = floatPreferencesKey("gender_conf")
        val THUMBS = booleanPreferencesKey("apply_thumbs")
        val PLAYBACK = booleanPreferencesKey("apply_playback")
        val WHITELIST = stringSetPreferencesKey("whitelist")
        val FEMALE_IDX = intPreferencesKey("female_idx")
        val FLIP_GENDER = booleanPreferencesKey("flip_gender")
        val NORM = stringPreferencesKey("norm")
        val WL_META = stringSetPreferencesKey("whitelist_meta")   // "id\u001Fname\u001Favatar"
        val SETUP_DONE = booleanPreferencesKey("model_setup_done")
        val EXCLUDED_VIDEOS = stringSetPreferencesKey("excluded_videos")
        val SCALE_SIZE = booleanPreferencesKey("scale_with_face_size")
        val MODEL_SOURCE = stringPreferencesKey("model_source")
    }

    val settings: Flow<BlurSettings> = ds.data.map { p ->
        val d = BlurSettings()
        BlurSettings(
            enabled = p[K.ENABLED] ?: d.enabled,
            blurFemale = p[K.FEMALE] ?: d.blurFemale,
            blurMale = p[K.MALE] ?: d.blurMale,
            blurUncertain = p[K.UNCERTAIN] ?: d.blurUncertain,
            mode = p[K.MODE]?.let { runCatching { BlurMode.valueOf(it) }.getOrNull() } ?: d.mode,
            strength = p[K.STRENGTH] ?: d.strength,
            facePaddingPct = p[K.PADDING] ?: d.facePaddingPct,
            minFaceSizePct = p[K.MIN_FACE] ?: d.minFaceSizePct,
            genderConfidence = p[K.CONF] ?: d.genderConfidence,
            applyToThumbnails = p[K.THUMBS] ?: d.applyToThumbnails,
            applyToPlayback = p[K.PLAYBACK] ?: d.applyToPlayback,
            whitelist = p[K.WHITELIST] ?: d.whitelist,
            femaleOutputIndex = p[K.FEMALE_IDX] ?: d.femaleOutputIndex,
            flipGender = p[K.FLIP_GENDER] ?: d.flipGender,
            normalization = p[K.NORM]?.let { runCatching { Normalization.valueOf(it) }.getOrNull() } ?: d.normalization,
            excludedVideos = p[K.EXCLUDED_VIDEOS] ?: d.excludedVideos,
            scaleWithFaceSize = p[K.SCALE_SIZE] ?: d.scaleWithFaceSize,
        )
    }

    /** First-launch model setup finished (imported or skipped). Survives settings reset. */
    val setupDone: Flow<Boolean> = ds.data.map { it[K.SETUP_DONE] ?: false }
    suspend fun markSetupDone() = ds.edit { it[K.SETUP_DONE] = true }

    suspend fun setEnabled(v: Boolean) = ds.edit { it[K.ENABLED] = v }
    suspend fun setBlurFemale(v: Boolean) = ds.edit { it[K.FEMALE] = v }
    suspend fun setBlurMale(v: Boolean) = ds.edit { it[K.MALE] = v }
    suspend fun setBlurUncertain(v: Boolean) = ds.edit { it[K.UNCERTAIN] = v }
    suspend fun setMode(v: BlurMode) = ds.edit { it[K.MODE] = v.name }
    suspend fun setStrength(v: Int) = ds.edit { it[K.STRENGTH] = v.coerceIn(5, 50) }
    suspend fun setPadding(v: Int) = ds.edit { it[K.PADDING] = v.coerceIn(0, 60) }
    suspend fun setMinFaceSize(v: Int) = ds.edit { it[K.MIN_FACE] = v.coerceIn(2, 30) }
    suspend fun setGenderConfidence(v: Float) = ds.edit { it[K.CONF] = v.coerceIn(0.5f, 0.95f) }
    suspend fun setApplyToThumbnails(v: Boolean) = ds.edit { it[K.THUMBS] = v }
    suspend fun setApplyToPlayback(v: Boolean) = ds.edit { it[K.PLAYBACK] = v }
    suspend fun setFemaleOutputIndex(v: Int) = ds.edit { it[K.FEMALE_IDX] = v.coerceIn(0, 1) }
    suspend fun setFlipGender(v: Boolean) = ds.edit { it[K.FLIP_GENDER] = v }
    suspend fun setNormalization(v: Normalization) = ds.edit { it[K.NORM] = v.name }
    suspend fun setScaleWithFaceSize(v: Boolean) = ds.edit { it[K.SCALE_SIZE] = v }

    /** Hugging Face repo (or link) the gender model is downloaded from. Empty = built-in default. */
    val modelSource: Flow<String> = ds.data.map { it[K.MODEL_SOURCE] ?: "" }
    suspend fun setModelSource(v: String) = ds.edit { it[K.MODEL_SOURCE] = v.trim() }
    /** Per-video override: never blur this one video (thumbnails and playback). */
    suspend fun setVideoExcluded(videoId: String, excluded: Boolean) = ds.edit { p ->
        val cur = p[K.EXCLUDED_VIDEOS] ?: emptySet()
        p[K.EXCLUDED_VIDEOS] = if (excluded) cur + videoId else cur - videoId
    }
    /** Whitelisted channels with display info (IDs set stays separate for O(1) checks). */
    val whitelistChannels: Flow<List<ChannelRef>> = ds.data.map { p ->
        val meta = (p[K.WL_META] ?: emptySet()).mapNotNull(ChannelRef::decode).associateBy { it.id }
        (p[K.WHITELIST] ?: emptySet()).map { meta[it] ?: ChannelRef(it, it, null) }.sortedBy { it.name.lowercase() }
    }

    suspend fun addToWhitelist(ch: ChannelRef) = ds.edit { p ->
        p[K.WHITELIST] = (p[K.WHITELIST] ?: emptySet()) + ch.id
        p[K.WL_META] = (p[K.WL_META] ?: emptySet()).filterNot { it.startsWith(ch.id + ChannelRef.SEP) }.toSet() + ch.encode()
    }
    suspend fun addToWhitelist(id: String) = addToWhitelist(ChannelRef(id, id, null))
    suspend fun removeFromWhitelist(id: String) = ds.edit { p ->
        p[K.WHITELIST] = (p[K.WHITELIST] ?: emptySet()) - id
        p[K.WL_META] = (p[K.WL_META] ?: emptySet()).filterNot { it.startsWith(id + ChannelRef.SEP) }.toSet()
    }
    suspend fun clearWhitelist() = ds.edit { it.remove(K.WHITELIST); it.remove(K.WL_META) }
    suspend fun resetToDefaults() = ds.edit { p ->
        val wl = p[K.WHITELIST]; val wm = p[K.WL_META]; val sd = p[K.SETUP_DONE]; p.clear()
        if (wl != null) p[K.WHITELIST] = wl; if (wm != null) p[K.WL_META] = wm; if (sd != null) p[K.SETUP_DONE] = sd   // keep whitelist + setup flag
    }
}
