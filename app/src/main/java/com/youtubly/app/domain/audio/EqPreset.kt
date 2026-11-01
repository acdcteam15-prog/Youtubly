package com.youtubly.app.domain.audio

import kotlin.math.ln

/**
 * Equalizer presets as gain curves over frequency, so they work on any device regardless of how many
 * bands its Equalizer has (usually 5, sometimes 6–10): each band's centre frequency is looked up on the
 * curve. Interpolation is linear in log-frequency, which is how hearing works.
 */
enum class EqPreset(val label: String, private val points: List<Pair<Int, Float>>) {
    OFF("Off", emptyList()),

    /** Lectures, podcasts, talks: cut rumble/boom, lift 1–4 kHz where consonants live, tame hiss. */
    SPEECH("Speech clarity", listOf(60 to -6f, 150 to -4f, 400 to -1f, 1000 to 2f, 2500 to 5f, 4000 to 4f, 8000 to 0f, 14000 to -3f)),

    /** Small phone speakers / quiet rooms: remove low end that only rattles. */
    BASS_CUT("Bass cut", listOf(60 to -9f, 150 to -6f, 400 to -2f, 1000 to 0f, 14000 to 0f)),

    BASS_BOOST("Bass boost", listOf(60 to 6f, 150 to 5f, 400 to 2f, 1000 to 0f, 14000 to 0f)),

    /** Dull recordings: open up the top. */
    TREBLE_BOOST("Treble boost", listOf(60 to 0f, 1000 to 0f, 4000 to 3f, 8000 to 5f, 14000 to 5f));

    /** Gain in dB at [freqHz] (0 when OFF). */
    fun gainDb(freqHz: Float): Float {
        if (points.isEmpty()) return 0f
        if (freqHz <= points.first().first) return points.first().second
        if (freqHz >= points.last().first) return points.last().second
        val i = points.indexOfLast { it.first <= freqHz }
        val (f0, g0) = points[i]
        val (f1, g1) = points[i + 1]
        val t = (ln(freqHz) - ln(f0.toFloat())) / (ln(f1.toFloat()) - ln(f0.toFloat()))
        return g0 + (g1 - g0) * t
    }

    /** Millibels for a band centred at [centerFreqMilliHz] (the Equalizer API reports milli-Hz), clamped to [minMb]..[maxMb]. */
    fun bandLevelMb(centerFreqMilliHz: Int, minMb: Int, maxMb: Int): Int =
        (gainDb(centerFreqMilliHz / 1000f) * 100).toInt().coerceIn(minMb, maxMb)

    companion object {
        fun fromName(name: String?): EqPreset = entries.firstOrNull { it.name == name } ?: OFF
    }
}
