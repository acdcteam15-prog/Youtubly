package com.youtubly.app.blur

import com.youtubly.app.blur.ModelOrigin.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledModelsTest {

    // ---- catalog integrity ----------------------------------------------------------------
    @Test fun idsAndFileNamesAreUnique() {
        val all = BundledModels.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.fileName }.toSet().size)
    }

    @Test fun everyFileIsATfliteInTheModelsFolder() {
        BundledModels.all.forEach {
            assertTrue(it.fileName, it.fileName.endsWith(".tflite"))
            assertEquals("models/${it.fileName}", it.assetPath)
        }
    }

    @Test fun exactlyOneModelIsTheDefault() {
        assertEquals(1, BundledModels.all.count { it.recommended })
        assertTrue(BundledModels.default.recommended)
    }

    @Test fun femaleIndexIsAValidOutputSlot() {
        BundledModels.all.forEach { assertTrue(it.id, it.femaleIndex in 0..1) }
    }

    @Test fun byIdFindsKnownAndRejectsUnknown() {
        assertNotNull(BundledModels.byId("utk_lite_q"))
        assertNull(BundledModels.byId("nope"))
        assertNull(BundledModels.byId(null))
        assertNull(BundledModels.byId(""))
    }

    /** The four UTKFace models are documented (by their own demo app) as index 0 = male, 1 = female. */
    @Test fun utkModelsTreatIndexOneAsFemale() {
        listOf("utk_lite_q", "utk_lite", "utk_q", "utk_full").forEach {
            val m = BundledModels.byId(it)!!
            assertEquals(it, 1, m.femaleIndex)
            assertEquals(it, Normalization.ZERO_ONE, m.normalization)
        }
    }

    /**
     * Measured on 8 labelled faces: this model's single value is P(male), so female = 1 - value.
     * Guards against "fixing" it back to 1, which would blur men instead of women out of the box.
     */
    @Test fun sigmoidModelIsMeasuredAsProbabilityOfMale() {
        val m = BundledModels.byId("mobilenet_sigmoid")!!
        assertEquals(0, m.femaleIndex)
    }

    // ---- origin markers ---------------------------------------------------------------------
    @Test fun markerRoundTrips() {
        assertEquals("bundled:utk_q", ModelOrigin.forModel("utk_q"))
        assertEquals("utk_q", ModelOrigin.bundledId("bundled:utk_q"))
        assertTrue(ModelOrigin.isBundled("bundled:utk_q"))
    }

    @Test fun nonBundledMarkersHaveNoId() {
        listOf("", "custom", "removed", "bundled:").forEach { assertNull(it, ModelOrigin.bundledId(it)) }
        assertFalse(ModelOrigin.isBundled("custom"))
        assertFalse(ModelOrigin.isBundled(""))
    }

    // ---- what happens on app start ----------------------------------------------------------
    @Test fun firstLaunchInstallsTheDefault() =
        assertEquals(Action.INSTALL_DEFAULT, ModelOrigin.onStart("", installed = false))

    @Test fun aModelImportedBeforeBundlingExistedIsKeptAsTheirs() =
        assertEquals(Action.MARK_CUSTOM, ModelOrigin.onStart("", installed = true))

    @Test fun aCustomModelIsNeverOverwritten() {
        assertEquals(Action.KEEP, ModelOrigin.onStart("custom", installed = true))
        assertEquals(Action.KEEP, ModelOrigin.onStart("custom", installed = false))
    }

    @Test fun aDeliberateRemovalIsNotSilentlyUndone() =
        assertEquals(Action.KEEP, ModelOrigin.onStart("removed", installed = false))

    @Test fun aChosenBundledModelIsKeptAcrossLaunches() =
        assertEquals(Action.KEEP, ModelOrigin.onStart("bundled:utk_full", installed = true))

    /** Upgrade from the older single-model build, whose marker was "bundled:<sha256>". Must not swap the model. */
    @Test fun anOlderBuildsShaMarkerIsLeftAlone() =
        assertEquals(Action.KEEP, ModelOrigin.onStart("bundled:" + "a".repeat(64), installed = true))

    @Test fun aMissingBundledFileIsReinstalled() =
        assertEquals(Action.INSTALL_DEFAULT, ModelOrigin.onStart("bundled:utk_q", installed = false))
}
