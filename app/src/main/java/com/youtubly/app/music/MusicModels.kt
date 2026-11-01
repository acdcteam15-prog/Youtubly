package com.youtubly.app.music

/** The separation models packed in the APK (assets/music_models/). Pure data, unit-tested. */
data class MusicModel(
    /** Stable id, saved in settings. Never change once shipped. */
    val id: String,
    val title: String,
    val blurb: String,
    val fileName: String,
    val sizeMb: Float,
) {
    val assetPath get() = "${MusicModels.ASSET_DIR}/$fileName"
}

object MusicModels {
    const val ASSET_DIR = "music_models"
    const val DEFAULT_ID = "balanced"

    val all: List<MusicModel> = listOf(
        MusicModel("light", "Light", "Fastest. Voice-only network, so more music leaks through.", "music_light.tflite", 9.9f),
        MusicModel("balanced", "Balanced", "Full Spleeter with compact weights. Good default.", "music_balanced.tflite", 19.7f),
        MusicModel("best", "Best", "Full Spleeter, highest precision. Heaviest; may be too slow live.", "music_best.tflite", 39.3f),
    )

    fun byId(id: String?): MusicModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
}

data class MusicSettings(
    val enabled: Boolean = false,
    val modelId: String = MusicModels.DEFAULT_ID,
    /** How much of the music is mixed back in: 0 = fully removed … 50 = half. */
    val musicLevelPct: Int = 0,
    val whitelist: Set<String> = emptySet(),
    val threads: Int = 4,
    /** 1 = normal, 2 = strong, 3 = max (see SpectralSeparator.maskPower). */
    val strength: Int = 1,
) {
    val musicLevel: Float get() = musicLevelPct.coerceIn(0, MAX_LEVEL_PCT) / 100f
    companion object { const val MAX_LEVEL_PCT = 50 }
}

/** Speed test result for one model on this phone. */
data class Benchmark(val modelId: String, val secondsPerBlock: Float, val blockSeconds: Float) {
    /** How many times faster than real time (> 1 means it can keep up). */
    val realtimeFactor: Float get() = blockSeconds / secondsPerBlock
    /** Live playback also needs headroom for decoding, video and the FFTs. */
    val verdict: Verdict get() = when {
        realtimeFactor >= 2f -> Verdict.GOOD
        realtimeFactor >= 1.2f -> Verdict.TIGHT
        else -> Verdict.TOO_SLOW
    }
    enum class Verdict { GOOD, TIGHT, TOO_SLOW }
}
