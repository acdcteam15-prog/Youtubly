package com.youtubly.app.music.dsp

import com.youtubly.app.music.dsp.SpectralSeparator.Companion.BLOCK
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.BLOCK_FRAMES
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.HOP
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.LOOKAHEAD
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.MODEL_FRAMES
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.N
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.PRE_FRAMES
import java.util.concurrent.Executor

/**
 * Streaming wrapper around [SpectralSeparator] (pure Kotlin, unit-tested; no Android types).
 *
 * Stereo float frames go in through [write]; the audio comes back out of [read] in the same order
 * and length, either processed (voice + [Policy.musicLevel] of the music) or untouched.
 * A worker ([executor]) separates whole blocks (~3 s) ahead of playback. When a block isn't ready
 * in time the original sound is played for that block instead of stalling; after [GIVE_UP_AFTER]
 * late blocks in a row the stream stays on the original sound until [reset].
 *
 * Positions count from the last [flush] (a seek), so each seek starts a fresh block grid.
 */
class BlockStream(
    private val policy: Policy,
    private val executor: Executor,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Tests run faster than real time and scale these down; the app uses the defaults. */
    private val firstWaitNs: Long = FIRST_WAIT_NS,
    private val lateWaitNs: Long = LATE_WAIT_NS,
    private val dataWaitNs: Long = DATA_WAIT_NS,
) {
    interface Policy {
        /** A separator for the current model, or null when no model can be loaded. */
        fun separator(): SpectralSeparator?
        fun wantsProcessing(): Boolean
        fun musicLevel(): Float
        fun previewOriginal(): Boolean
        fun onStatus(status: Status)
        /** Every block as it starts playing: processed or original sound. */
        fun onBlock(processed: Boolean) {}
        fun onError(t: Throwable) {}
    }
    enum class Status { PROCESSING, BYPASSED, TOO_SLOW, NO_MODEL }
    private enum class Mode { PROCESSED, RAW }

    private val lock = Any()
    private var left = FloatArray(CAPACITY)
    private var right = FloatArray(CAPACITY)
    private var base = 0L            // absolute frame held at index 0 of left/right
    private var written = 0L
    private var emitted = 0L
    private var inputEnded = false
    private var generation = 0
    private val results = HashMap<Long, FloatArray>()
    private val failed = HashSet<Long>()
    private var nextToSchedule = 0L
    private var inFlight = false
    private var mode: Mode? = null
    private var waitStart = 0L
    private var dataWaitStart = 0L
    private var lateInARow = 0
    private var gaveUp = false

    /**
     * Appends [count] stereo frames from [l]/[r]. Always takes everything: Media3's audio sink
     * spins forever on a processor that refuses input, so back-pressure must come from the
     * output side (if too much piles up while waiting, the current block plays untouched).
     */
    fun write(l: FloatArray, r: FloatArray, count: Int): Int {
        val n = count
        synchronized(lock) {
            if (n == 0) return 0
            ensureRoom(n)
            val at = (written - base).toInt()
            System.arraycopy(l, 0, left, at, n)
            System.arraycopy(r, 0, right, at, n)
            written += n
        }
        schedule()
        return n
    }

    fun endOfInput() { synchronized(lock) { inputEnded = true }; schedule() }

    val isEnded: Boolean get() = synchronized(lock) { inputEnded && emitted >= written }

    /**
     * Up to [maxFrames] frames of output into [outL]/[outR]; 0 = nothing yet (a block is still being
     * separated and there is time to wait for it).
     */
    fun read(outL: FloatArray, outR: FloatArray, maxFrames: Int): Int {
        var status: Status? = null
        val n: Int
        synchronized(lock) {
            val k = emitted / BLOCK
            if (mode == null) {
                val want = policy.wantsProcessing()
                // Keep a fixed amount of audio ahead of playback. The sink only hands over input as
                // fast as output plays, so without this reserve the next block's audio arrives just
                // before it's due and the model has no time. Holding back output at a block boundary
                // makes the sink push input immediately (decoding is much faster than real time).
                val short = want && !gaveUp && !inputEnded && written - emitted < RESERVE
                if (short) {
                    val now = nanoTime()
                    if (dataWaitStart == 0L) dataWaitStart = now
                    if (now - dataWaitStart < dataWaitNs) return 0   // input is on its way
                }
                dataWaitStart = 0L
                mode = when {
                    !want -> { status = Status.BYPASSED; Mode.RAW }
                    gaveUp -> Mode.RAW
                    results.containsKey(k) -> { lateInARow = 0; status = Status.PROCESSING; Mode.PROCESSED }
                    k in failed -> { status = Status.NO_MODEL; Mode.RAW }
                    else -> {
                        val now = nanoTime()
                        if (waitStart == 0L) waitStart = now
                        val budget = if (k == 0L) firstWaitNs else lateWaitNs
                        // Memory guard only. The sink pushes input in bursts, so a large backlog is
                        // normal and must NOT count as "too slow".
                        val overflow = written - emitted > MAX_BACKLOG
                        if (overflow) Mode.RAW
                        else if (now - waitStart < budget && !(inputEnded && written <= k * BLOCK)) null
                        else {
                            // Only a block the model had in hand counts as "too slow"; a block whose
                            // audio hadn't arrived yet (network) is not the model's fault.
                            if (k < nextToSchedule || inFlight) {   // model had it, or is still busy
                                lateInARow++
                                if (lateInARow >= GIVE_UP_AFTER) { gaveUp = true; status = Status.TOO_SLOW }
                            }
                            Mode.RAW
                        }
                    }
                }
                if (mode != null) { waitStart = 0L; policy.onBlock(mode == Mode.PROCESSED && policy.wantsProcessing()) }
            }
            val m = mode
            if (m == null) { n = 0 } else {
                val blockEnd = (k + 1) * BLOCK
                n = minOf(maxFrames.toLong(), blockEnd - emitted, written - emitted).toInt().coerceAtLeast(0)
                val voice = if (m == Mode.PROCESSED) results[k] else null
                val original = voice == null || policy.previewOriginal()
                val level = policy.musicLevel()
                var src = (emitted - base).toInt()
                var off = ((emitted - k * BLOCK) * 2).toInt()
                for (i in 0 until n) {
                    val a = left[src]; val b = right[src]
                    if (original) { outL[i] = a; outR[i] = b }
                    else {
                        val vl = voice!![off]; val vr = voice[off + 1]
                        outL[i] = vl + level * (a - vl); outR[i] = vr + level * (b - vr)
                    }
                    src++; off += 2
                }
                emitted += n
                if (emitted == blockEnd) { results.remove(k); failed.remove(k); mode = null }
            }
        }
        status?.let(policy::onStatus)
        if (n > 0) schedule()
        return n
    }

    /** Seek: drop everything buffered and start a new block grid (keeps the give-up state). */
    fun flush() = synchronized(lock) {
        generation++
        dataWaitStart = 0
        base = 0; written = 0; emitted = 0; inputEnded = false
        results.clear(); failed.clear(); nextToSchedule = 0; inFlight = false
        mode = null; waitStart = 0; lateInARow = 0
    }

    /** New item / new format: also forgets that this phone fell behind. */
    fun reset() = synchronized(lock) { flush(); gaveUp = false }

    // ---------------------------------------------------------------------------------------

    private fun ensureRoom(n: Int) {
        if (written + n - base <= left.size) return
        val schedBlock = maxOf(nextToSchedule, emitted / BLOCK)
        val keepFrom = minOf(emitted, (schedBlock * BLOCK_FRAMES - PRE_FRAMES) * HOP).coerceAtLeast(base)
        val shift = (keepFrom - base).toInt()
        val len = (written - keepFrom).toInt()
        System.arraycopy(left, shift, left, 0, len)
        System.arraycopy(right, shift, right, 0, len)
        base = keepFrom
        if (written + n - base > left.size) {
            val size = maxOf(left.size * 2, (written + n - base).toInt())
            left = left.copyOf(size); right = right.copyOf(size)
        }
    }

    private fun schedule() {
        val job: Runnable
        synchronized(lock) {
            if (inFlight || gaveUp || !policy.wantsProcessing()) return
            val current = emitted / BLOCK
            val j = maxOf(nextToSchedule, if (mode == null) current else current + 1)
            if (j > current + MAX_BLOCKS_AHEAD) return
            val need = (j + 1) * BLOCK + LOOKAHEAD
            if (written < need && !inputEnded) return
            if (j * BLOCK >= written) return
            // Copy exactly the model window, zero-padded, so the worker needs no lock.
            val from = (j * BLOCK_FRAMES - PRE_FRAMES) * HOP
            val len = (MODEL_FRAMES - 1) * HOP + N
            val cl = FloatArray(len); val cr = FloatArray(len)
            val lo = maxOf(from, base); val hi = minOf(from + len, written)
            if (hi > lo) {
                System.arraycopy(left, (lo - base).toInt(), cl, (lo - from).toInt(), (hi - lo).toInt())
                System.arraycopy(right, (lo - base).toInt(), cr, (lo - from).toInt(), (hi - lo).toInt())
            }
            nextToSchedule = j + 1
            inFlight = true
            val gen = generation
            job = Runnable {
                val voice = runCatching {
                    val sep = policy.separator() ?: error("model could not be loaded")
                    sep.processBlock(j, SpectralSeparator.Source { c, i ->
                        val x = i - from
                        if (x < 0 || x >= len) 0f else if (c == 0) cl[x.toInt()] else cr[x.toInt()]
                    })
                }.onFailure { policy.onError(it) }.getOrNull()
                synchronized(lock) {
                    if (gen != generation) return@synchronized
                    inFlight = false
                    if (j < emitted / BLOCK) return@synchronized   // too late, already played untouched
                    if (voice != null) results[j] = voice else failed += j
                }
                schedule()
            }
        }
        executor.execute(job)
    }

    companion object {
        private const val AHEAD = 4L * BLOCK
        /** ~95 s at 44.1 kHz (≈ 34 MB of floats): beyond this, play untouched rather than grow. */
        private const val MAX_BACKLOG = 32L * BLOCK
        private const val CAPACITY = 5 * BLOCK + 64 * HOP
        private const val MAX_BLOCKS_AHEAD = 3
        /** After a seek / start, wait this long for the first block before playing it untouched. */
        private const val FIRST_WAIT_NS = 1_500_000_000L
        /** Later blocks should be ready long before they're due; this only covers jitter. */
        private const val LATE_WAIT_NS = 250_000_000L
        const val GIVE_UP_AFTER = 2
        /**
         * Audio kept ahead of playback: the block being played plus the next block and its
         * lookahead, so each block's input is complete one full block (~3 s) before it's due.
         */
        const val RESERVE = 2L * BLOCK + LOOKAHEAD
        /** If input doesn't arrive (network stall), stop holding back after this long. */
        private const val DATA_WAIT_NS = 1_000_000_000L
    }
}
