package com.youtubly.app.blur

enum class BlurMode { GAUSSIAN, PIXELATE, SOLID }
enum class Normalization { ZERO_ONE, MINUS_ONE_ONE, RAW }
enum class BlurSurface { THUMBNAIL, PLAYBACK, PREVIEW }
enum class Gender { FEMALE, MALE, UNKNOWN }

/** How much of a person is hidden once their face is found. */
enum class BodyCoverage { FACE, UPPER_BODY, FULL_BODY }

/** Which genders get hidden (face or body), used for a single channel's own rule. */
enum class GenderRule { FEMALE, MALE, BOTH }

/**
 * One channel's own blur choices on top of the general ones; null = follow the general setting.
 * ("Never blur this channel" is the existing excluded-channels list, not part of this.)
 */
@kotlinx.serialization.Serializable
data class ChannelBlurOverride(
    /** true = blur this channel even when blur is switched off in general. */
    val force: Boolean? = null,
    val coverage: BodyCoverage? = null,
    val gender: GenderRule? = null
) {
    val isEmpty: Boolean get() = force == null && coverage == null && gender == null

    companion object {
        private val mapSerializer = kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.serializer<String>(), ChannelBlurOverride.serializer()
        )
        fun decodeMap(text: String?): Map<String, ChannelBlurOverride> =
            if (text.isNullOrBlank()) emptyMap()
            else runCatching { kotlinx.serialization.json.Json.decodeFromString(mapSerializer, text) }.getOrDefault(emptyMap())
        fun encodeMap(map: Map<String, ChannelBlurOverride>): String = kotlinx.serialization.json.Json.encodeToString(mapSerializer, map)
    }
}

/** The settings as they apply to one channel: its own coverage, gender rule and always-on choice on top of the general ones. */
fun BlurSettings.forChannel(channelKey: String?): BlurSettings {
    val ov = channelKey?.let { channelOverrides[it] } ?: return this
    var e = this
    ov.coverage?.let { e = e.copy(bodyCoverage = it) }
    when (ov.gender) {
        GenderRule.FEMALE -> e = e.copy(blurFemale = true, blurMale = false)
        GenderRule.MALE -> e = e.copy(blurFemale = false, blurMale = true)
        GenderRule.BOTH -> e = e.copy(blurFemale = true, blurMale = true)
        null -> Unit
    }
    if (ov.force == true) e = e.copy(enabled = true)
    return e
}

data class BlurSettings(
    val enabled: Boolean = false,
    val blurFemale: Boolean = true,
    val blurMale: Boolean = false,
    val blurUncertain: Boolean = true,          // blur when model unsure / missing
    val mode: BlurMode = BlurMode.GAUSSIAN,
    val strength: Int = 30,                     // 5..50
    val facePaddingPct: Int = 25,               // 0..60 % around face box
    val bodyCoverage: BodyCoverage = BodyCoverage.FULL_BODY, // face only / head to waist / head to toe
    val channelOverrides: Map<String, ChannelBlurOverride> = emptyMap(),   // per-channel coverage / gender / always-on
    val coverBackViews: Boolean = true,         // also hide people whose face can't be seen (needs body coverage)
    val minFaceSizePct: Int = 5,                // 2..30 % of image width
    val genderConfidence: Float = 0.70f,        // 0.5..0.95
    val applyToThumbnails: Boolean = true,
    val applyToPlayback: Boolean = true,
    val whitelist: Set<String> = emptySet(),    // channel IDs
    val excludedVideos: Set<String> = emptySet(), // video IDs the user chose not to blur
    val scaleWithFaceSize: Boolean = true,      // bigger faces get proportionally stronger blur
    val femaleOutputIndex: Int = 0,             // model output order (custom files only)
    val flipGender: Boolean = false,            // swap male/female for a built-in model that reads inverted
    val normalization: Normalization = Normalization.ZERO_ONE,
) {
    val needsDetection get() = blurFemale || blurMale
    val needsClassification get() = blurFemale != blurMale
}
