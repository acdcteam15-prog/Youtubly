package com.youtubly.app.update

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Rebuilds a new APK from the installed one plus a small patch (format YTDIFF01, made by scripts/ci/make_patch.py).
 * This is how an update can be a few megabytes instead of the whole file.
 *
 * It streams: the old file is read in pieces and the new one written as it is produced, so memory stays small even
 * for a 200 MB APK. Both ends are checked by SHA-256: the installed file must be the one the patch was made for, and
 * the rebuilt file must be the one that was published. Nothing is installed from a failed attempt.
 */
object PatchApplier {
    class PatchException(message: String) : IOException(message)

    private const val MAGIC = "YTDIFF01"
    private const val HEADER = 8 + 8 + 8 + 32 + 32 + 8 + 8 + 8

    /** Rebuilds [outFile] from [oldFile] and [patchFile]. [progress] gets 0..1 while it writes. */
    fun apply(oldFile: File, patchFile: File, outFile: File, progress: (Float) -> Unit = {}) {
        val header = DataInputStream(BufferedInputStream(FileInputStream(patchFile))).use { input ->
            val magic = ByteArray(8).also { input.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw PatchException("This is not an update patch.")
            val oldSize = input.readLong()
            val newSize = input.readLong()
            val oldSha = ByteArray(32).also { input.readFully(it) }
            val newSha = ByteArray(32).also { input.readFully(it) }
            Header(oldSize, newSize, oldSha, newSha, input.readLong(), input.readLong(), input.readLong())
        }
        if (header.newSize <= 0 || header.ctrlLen < 0 || header.diffLen < 0 || header.extraLen < 0) throw PatchException("The patch is damaged.")
        if (oldFile.length() != header.oldSize || !sha256(oldFile).contentEquals(header.oldSha)) {
            throw PatchException("This installed copy is not the version the patch was made for.")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val ctrlStart = HEADER.toLong()
        val diffStart = ctrlStart + header.ctrlLen
        val extraStart = diffStart + header.diffLen
        if (extraStart + header.extraLen > patchFile.length()) throw PatchException("The patch is incomplete.")

        RandomAccessFile(oldFile, "r").use { old ->
            DataInputStream(section(patchFile, ctrlStart, header.ctrlLen)).use { ctrl ->
                section(patchFile, diffStart, header.diffLen).use { diff ->
                    section(patchFile, extraStart, header.extraLen).use { extra ->
                        BufferedOutputStream(FileOutputStream(outFile), 256 * 1024).use { out ->
                            val buf = ByteArray(64 * 1024)
                            val oldBuf = ByteArray(64 * 1024)
                            var oldPos = 0L
                            var newPos = 0L
                            var lastReport = 0L

                            fun emit(n: Int) { out.write(buf, 0, n); digest.update(buf, 0, n); newPos += n }

                            while (newPos < header.newSize) {
                                val x = readOfft(ctrl)      // bytes to add (old + difference)
                                val y = readOfft(ctrl)      // bytes copied as they are
                                val z = readOfft(ctrl)      // how far to move in the old file afterwards
                                if (x < 0 || y < 0 || newPos + x + y > header.newSize) throw PatchException("The patch is damaged.")

                                var left = x
                                while (left > 0) {
                                    val n = minOf(left, buf.size.toLong()).toInt()
                                    readFully(diff, buf, n)
                                    // old bytes at oldPos .. oldPos + n (anything outside the old file counts as 0)
                                    java.util.Arrays.fill(oldBuf, 0, n, 0)
                                    val from = maxOf(oldPos, 0L)
                                    val to = minOf(oldPos + n, header.oldSize)
                                    if (to > from) {
                                        old.seek(from)
                                        old.readFully(oldBuf, (from - oldPos).toInt(), (to - from).toInt())
                                    }
                                    for (i in 0 until n) buf[i] = (buf[i] + oldBuf[i]).toByte()
                                    oldPos += n
                                    left -= n
                                    emit(n)
                                    if (newPos - lastReport > 4L * 1024 * 1024) { lastReport = newPos; progress(newPos.toFloat() / header.newSize) }
                                }
                                left = y
                                while (left > 0) {
                                    val n = minOf(left, buf.size.toLong()).toInt()
                                    readFully(extra, buf, n)
                                    left -= n
                                    emit(n)
                                }
                                oldPos += z
                            }
                        }
                    }
                }
            }
        }
        if (!digest.digest().contentEquals(header.newSha)) {
            outFile.delete()
            throw PatchException("The rebuilt update does not match the published one.")
        }
        progress(1f)
    }

    private class Header(
        val oldSize: Long, val newSize: Long, val oldSha: ByteArray, val newSha: ByteArray,
        val ctrlLen: Long, val diffLen: Long, val extraLen: Long
    )

    /** A gzip stream reading just [length] bytes of [file] from [offset]. */
    private fun section(file: File, offset: Long, length: Long): InputStream {
        val raf = RandomAccessFile(file, "r")
        raf.seek(offset)
        val bounded = object : InputStream() {
            var left = length
            override fun read(): Int {
                if (left <= 0) return -1
                val b = raf.read()
                if (b >= 0) left--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() = raf.close()
        }
        return GZIPInputStream(BufferedInputStream(bounded, 64 * 1024), 64 * 1024)
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int) {
        var done = 0
        while (done < n) {
            val r = input.read(buf, done, n - done)
            if (r < 0) throw PatchException("The patch ended too early.")
            done += r
        }
    }

    /** bsdiff's number format: 8 bytes, little endian, sign in the top bit. */
    internal fun offtin(b: ByteArray): Long {
        var y = (b[7].toInt() and 0x7F).toLong()
        for (i in 6 downTo 0) y = y * 256 + (b[i].toInt() and 0xFF)
        return if (b[7].toInt() and 0x80 != 0) -y else y
    }

    private fun readOfft(input: DataInputStream): Long = offtin(ByteArray(8).also { input.readFully(it) })

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}
