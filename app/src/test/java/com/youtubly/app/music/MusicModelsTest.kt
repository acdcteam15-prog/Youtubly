package com.youtubly.app.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MusicModelsTest {
    @Test fun idsAreUniqueAndDefaultExists() {
        assertEquals(MusicModels.all.size, MusicModels.all.map { it.id }.toSet().size)
        assertEquals(MusicModels.DEFAULT_ID, MusicModels.byId("nope").id)
    }

    @Test fun everyModelIsPackedInAssets() {
        MusicModels.all.forEach { m ->
            val f = File("src/main/assets/${m.assetPath}")
            assertTrue("missing ${f.path}", f.isFile && f.length() > 1_000_000)
        }
    }

    @Test fun verdictThresholds() {
        assertEquals(Benchmark.Verdict.GOOD, Benchmark("x", 1f, 3f).verdict)
        assertEquals(Benchmark.Verdict.TIGHT, Benchmark("x", 2f, 3f).verdict)
        assertEquals(Benchmark.Verdict.TOO_SLOW, Benchmark("x", 3f, 3f).verdict)
    }
}
