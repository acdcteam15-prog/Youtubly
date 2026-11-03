package com.youtubly.app.blur

enum class BlurMode { GAUSSIAN, PIXELATE, SOLID }
enum class Normalization { ZERO_ONE, MINUS_ONE_ONE, RAW }
enum class BlurSurface { THUMBNAIL, PLAYBACK, PREVIEW }
enum class Gender { FEMALE, MALE, UNKNOWN }

/** How much of a person is hidden once their face is found. */
enum class BodyCoverage { FACE, UPPER_BODY, FULL_BODY }

data class BlurSettings(
    val enabled: Boolean = false,
    val blurFemale: Boolean = true,
    val blurMale: Boolean = false,
    val blurUncertain: Boolean = true,          // blur when model unsure / missing
    val mode: BlurMode = BlurMode.GAUSSIAN,
    val strength: Int = 30,                     // 5..50
    val facePaddingPct: Int = 25,               // 0..60 % around face box
    val bodyCoverage: BodyCoverage = BodyCoverage.FULL_BODY, // face only / head to waist / head to toe
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
