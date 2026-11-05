package com.youtubly.app.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.zip.GZIPOutputStream

class PatchApplierTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** bsdiff's number format: 8 bytes little endian, sign in the top bit. */
    private fun offt(v: Long): ByteArray {
        var y = Math.abs(v)
        val b = ByteArray(8)
        for (i in 0 until 8) { b[i] = (y % 256).toByte(); y /= 256 }
        if (v < 0) b[7] = (b[7].toInt() or 0x80).toByte()
        return b
    }

    /** Writes a YTDIFF01 patch from explicit control triples (x = add, y = copy, z = move in the old file). */
    private fun patch(old: ByteArray, new: ByteArray, triples: List<Triple<Long, Long, Long>>, diff: ByteArray, extra: ByteArray): File {
        val ctrl = ByteArrayOutputStream().also { o -> triples.forEach { (x, y, z) -> o.write(offt(x)); o.write(offt(y)); o.write(offt(z)) } }.toByteArray()
        val gc = gz(ctrl); val gd = gz(diff); val ge = gz(extra)
        val f = tmp.newFile()
        DataOutputStream(f.outputStream()).use { out ->
            out.write("YTDIFF01".toByteArray()); out.writeLong(old.size.toLong()); out.writeLong(new.size.toLong())
            out.write(sha(old)); out.write(sha(new)); out.writeLong(gc.size.toLong()); out.writeLong(gd.size.toLong()); out.writeLong(ge.size.toLong())
            out.write(gc); out.write(gd); out.write(ge)
        }
        return f
    }

    private fun file(b: ByteArray) = tmp.newFile().also { it.writeBytes(b) }

    @Test fun `edited bytes plus new data at the end are rebuilt exactly`() {
        val old = ByteArray(200_000).also { Random(1).nextBytes(it) }
        val new = old.copyOf(old.size + 5_000).also { for (i in 0 until 300) it[i * 600] = (it[i * 600] + 1).toByte(); Random(2).nextBytes(ByteArray(0)); for (i in old.size until it.size) it[i] = (i % 251).toByte() }
        val diff = ByteArray(old.size) { (new[it] - old[it]).toByte() }
        val extra = new.copyOfRange(old.size, new.size)
        val p = patch(old, new, listOf(Triple(old.size.toLong(), extra.size.toLong(), 0L)), diff, extra)
        val out = tmp.newFile()
        var last = 0f
        PatchApplier.apply(file(old), p, out) { last = it }
        assertArrayEquals(new, out.readBytes())
        assertEquals(1f, last, 0f)
    }

    @Test fun `data that moved is rebuilt by seeking backwards and forwards in the old file`() {
        val old = ByteArray(1_000).also { Random(3).nextBytes(it) }
        val new = old.copyOfRange(100, 200) + old.copyOfRange(0, 100)
        // move to 100, add 100 bytes, jump back to 0, add 100 bytes
        val triples = listOf(Triple(0L, 0L, 100L), Triple(100L, 0L, -200L), Triple(100L, 0L, 0L))
        val p = patch(old, new, triples, ByteArray(200), ByteArray(0))
        val out = tmp.newFile()
        PatchApplier.apply(file(old), p, out)
        assertArrayEquals(new, out.readBytes())
    }

    @Test fun `a different installed file is refused`() {
        val old = ByteArray(500).also { Random(4).nextBytes(it) }
        val p = patch(old, old, listOf(Triple(500L, 0L, 0L)), ByteArray(500), ByteArray(0))
        val other = old.copyOf().also { it[0] = (it[0] + 1).toByte() }
        try { PatchApplier.apply(file(other), p, tmp.newFile()); fail("should refuse") }
        catch (e: PatchApplier.PatchException) { assertTrue(e.message!!.contains("not the version")) }
    }

    @Test fun `a damaged patch never produces a file`() {
        val old = ByteArray(2_000).also { Random(5).nextBytes(it) }
        val new = old.copyOf().also { it[10] = (it[10] + 9).toByte() }
        val diff = ByteArray(old.size) { (new[it] - old[it]).toByte() }
        // the diff block says "no change" but the published file has a change: the final SHA-256 catches it
        val p = patch(old, new, listOf(Triple(old.size.toLong(), 0L, 0L)), ByteArray(old.size), ByteArray(0))
        val out = tmp.newFile()
        try { PatchApplier.apply(file(old), p, out); fail("should fail the final check") }
        catch (e: PatchApplier.PatchException) { assertTrue(e.message!!.contains("does not match")) }
        assertFalse(out.exists())
        assertTrue(diff.isNotEmpty())
    }

    @Test fun `junk and truncated files are rejected`() {
        val old = file(ByteArray(10))
        try { PatchApplier.apply(old, file(ByteArray(300) { 7 }), tmp.newFile()); fail() } catch (e: PatchApplier.PatchException) { assertTrue(e.message!!.contains("not an update patch")) }
        val real = patch(ByteArray(10), ByteArray(10), listOf(Triple(10L, 0L, 0L)), ByteArray(10), ByteArray(0)).readBytes()
        try { PatchApplier.apply(old, file(real.copyOf(real.size - 8)), tmp.newFile()); fail() } catch (e: java.io.IOException) { /* incomplete or cut gzip */ }
    }

    @Test fun `bsdiff numbers decode with their sign`() {
        for (v in listOf(0L, 1L, 255L, 256L, 123_456_789L, -1L, -300L, -9_999_999_999L)) assertEquals(v, PatchApplier.offtin(offt(v)))
    }
}
