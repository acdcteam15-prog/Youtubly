package com.youtubly.app.core

/**
 * Turns NewPipe Extractor failures into something a person can act on.
 *
 * YouTube changes its page format every few weeks, and a pinned extractor then fails with
 * ParsingException / ExtractionException for *every* video. That's different from one video being
 * private or removed, so the two get different messages.
 *
 * Matching is by class *name* across the superclass chain, not by import, so it keeps working when
 * NewPipe moves or adds exception types between versions (and it's testable without NewPipe).
 */
object ExtractorHealth {

    enum class Kind { OUTDATED, UNAVAILABLE, AGE_RESTRICTED, REGION_BLOCKED, BOT_CHECK, OTHER }

    /** Exception names that mean "this particular video can't be played". */
    private val unavailable = setOf(
        "ContentNotAvailableException", "PrivateContentException", "PaidContentException",
        "YoutubeMusicPremiumContentException", "AccountTerminatedException",
    )

    fun classify(t: Throwable): Kind {
        for (e in generateSequence(t) { it.cause }) {
            val names = generateSequence<Class<*>>(e.javaClass) { it.superclass }.map { it.simpleName }.toList()
            when {
                "AgeRestrictedContentException" in names -> return Kind.AGE_RESTRICTED
                "GeographicRestrictionException" in names -> return Kind.REGION_BLOCKED
                "ReCaptchaException" in names || "SignInConfirmNotBotException" in names -> return Kind.BOT_CHECK
                names.any { it in unavailable } -> return Kind.UNAVAILABLE
                // Checked last: the specific types above are themselves ParsingException subclasses.
                "ParsingException" in names || "ExtractionException" in names -> return Kind.OUTDATED
            }
        }
        return Kind.OTHER
    }

    /** A friendly message, or null when this isn't an extractor problem. */
    fun message(t: Throwable): String? = when (classify(t)) {
        Kind.OUTDATED -> "Playback source is outdated. YouTube changed something the built-in extractor " +
            "(NewPipe ${com.youtubly.app.BuildConfig.NEWPIPE_VERSION}) can't read yet. Update the app when a new version is out."
        Kind.UNAVAILABLE -> "This video isn't available: it may be private, removed, or members-only."
        Kind.AGE_RESTRICTED -> "This video is age-restricted and can't be played without an age-verified account."
        Kind.REGION_BLOCKED -> "This video isn't available in your region."
        Kind.BOT_CHECK -> "YouTube is asking to confirm you're not a bot. Wait a few minutes, or switch network, then try again."
        Kind.OTHER -> null
    }
}
