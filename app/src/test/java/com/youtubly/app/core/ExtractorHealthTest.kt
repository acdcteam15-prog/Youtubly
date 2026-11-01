package com.youtubly.app.core

import com.youtubly.app.core.ExtractorHealth.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Stand-ins named like NewPipe's exceptions: matching is by class name, not by type. */
private open class ExtractionException(m: String = "x") : Exception(m)
private open class ParsingException(m: String = "x") : ExtractionException(m)
private class ContentNotAvailableException : ParsingException()
private class AgeRestrictedContentException : ParsingException()
private class GeographicRestrictionException : ParsingException()
private class ReCaptchaException : ExtractionException()
private class SomeNewParserFailure : ParsingException()   // unknown subclass → still "outdated"

class ExtractorHealthTest {
    @Test fun parsingFailuresMeanOutdated() {
        assertEquals(Kind.OUTDATED, ExtractorHealth.classify(ParsingException()))
        assertEquals(Kind.OUTDATED, ExtractorHealth.classify(SomeNewParserFailure()))
    }

    @Test fun specificSubclassesWinOverOutdated() {
        assertEquals(Kind.UNAVAILABLE, ExtractorHealth.classify(ContentNotAvailableException()))
        assertEquals(Kind.AGE_RESTRICTED, ExtractorHealth.classify(AgeRestrictedContentException()))
        assertEquals(Kind.REGION_BLOCKED, ExtractorHealth.classify(GeographicRestrictionException()))
        assertEquals(Kind.BOT_CHECK, ExtractorHealth.classify(ReCaptchaException()))
    }

    @Test fun findsCauseInsideWrapper() {
        assertEquals(Kind.OUTDATED, ExtractorHealth.classify(RuntimeException("wrapped", ParsingException())))
    }

    @Test fun ordinaryErrorsAreNotExtractorProblems() {
        assertEquals(Kind.OTHER, ExtractorHealth.classify(java.io.IOException("timeout")))
        assertNull(ExtractorHealth.message(IllegalStateException()))
        assertNotNull(ExtractorHealth.message(ParsingException()))
    }
}
