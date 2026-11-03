package com.youtubly.app.blur.playback

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * GPU side of playback blurring. Every frame: draws the video through a shader that hides the
 * current face regions (blur / pixelate / solid, feathered ellipses). Every ~[DETECT_EVERY_MS]:
 * copies a small downscaled frame to CPU and hands it to [FaceTracker] off the GL thread.
 */
@OptIn(UnstableApi::class)
internal class FaceBlurShaderProgram(
    private val tracker: FaceTracker,
    useHdr: Boolean = false,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val program = GlProgram(VERTEX, FRAGMENT).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }
    private var width = 0
    private var height = 0

    // Small readback target for detection
    private var sampleTex = -1
    private var sampleFbo = -1
    private var sampleW = 0
    private var sampleH = 0
    private var sampleBuf: ByteBuffer? = null
    private var rawSample: Bitmap? = null
    private var uprightSample: Bitmap? = null
    private val flipMatrix = Matrix()
    private var lastSampleAt = 0L

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth; height = inputHeight
        releaseSample()
        sampleW = SAMPLE_WIDTH
        sampleH = (SAMPLE_WIDTH * inputHeight.toFloat() / inputWidth).roundToInt().coerceAtLeast(16)
        tracker.reset()
        // The detection readback target is optional: if the GPU refuses it, keep the video playing
        // and hide the whole frame (fail-closed) instead of failing playback outright.
        try {
            sampleTex = GlUtil.createTexture(sampleW, sampleH, false)
            sampleFbo = GlUtil.createFboForTexture(sampleTex)
            sampleBuf = ByteBuffer.allocateDirect(sampleW * sampleH * 4).order(ByteOrder.nativeOrder())
        } catch (t: Throwable) {
            runCatching { releaseSample() }
            sampleTex = -1; sampleFbo = -1; sampleBuf = null
            tracker.markFailed()
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val active = tracker.isActive()
            // maybeSample is a side-channel (GPU→CPU readback for detection). It must never be able
            // to stop the real frame below from drawing: any failure in it is swallowed and detection
            // just sits out this frame, instead of the whole video going blank (dropped frame — the
            // white/blank screen bug).
            if (active) try { maybeSample(inputTexId) } catch (_: Throwable) { restoreMainTarget(); tracker.markFailed() }

            val s = tracker.settings()
            val regions = if (active) tracker.currentRegions() else emptyList()
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            setRegions(regions)
            program.setFloatUniform("uMode", if (regions.isEmpty()) -1f else s.mode.ordinal.toFloat())
            program.setFloatUniform("uStrength", s.strength / 50f)
            program.setFloatUniform("uAspect", width.toFloat() / height)
            program.setFloatUniform("uShape", if (s.bodyCoverage == com.youtubly.app.blur.BodyCoverage.FACE) 2f else 6f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** The main output framebuffer/viewport that queueInputFrame focused before calling drawFrame,
     *  captured once per frame in maybeSample so a failure partway through can restore it. */
    private var mainFbo = 0
    private var mainViewport = IntArray(4)

    /** Throttled GPU→CPU copy of a small frame. The caller's framebuffer is restored afterwards
     *  (including on failure — see the catch in [drawFrame]), so a sampling error never leaves the
     *  real video frame drawing into the wrong target. */
    private fun maybeSample(inputTexId: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < DETECT_EVERY_MS || tracker.busy) return
        lastSampleAt = now

        val prevFbo = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }
        val vp = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
        mainFbo = prevFbo[0]; mainViewport = vp
        if (sampleFbo == -1 || sampleTex == -1 || sampleBuf == null) { tracker.markFailed(); return }   // no readback target: fail closed

        try {
            GlUtil.focusFramebufferUsingCurrentContext(sampleFbo, sampleW, sampleH)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            setRegions(emptyList())
            program.setFloatUniform("uMode", -1f)             // passthrough copy
            program.setFloatUniform("uStrength", 0f)
            program.setFloatUniform("uAspect", 1f)
            program.setFloatUniform("uShape", 2f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val buf = sampleBuf!!.apply { rewind() }
            GLES20.glReadPixels(0, 0, sampleW, sampleH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

            // RGBA bytes == ARGB_8888 memory layout. GL rows are bottom-up → flip for the detector.
            // Both bitmaps are reused across samples (was two new ~0.2 MB bitmaps ~10×/s).
            // Safe: we only get here when the tracker isn't busy with the previous one.
            val raw = rawSample?.takeIf { it.width == sampleW && it.height == sampleH && !it.isRecycled }
                ?: Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888).also { rawSample = it }
            val upright = uprightSample?.takeIf { it.width == sampleW && it.height == sampleH && !it.isRecycled }
                ?: Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888).also { uprightSample = it }
            buf.rewind(); raw.copyPixelsFromBuffer(buf)
            flipMatrix.setScale(1f, -1f); flipMatrix.postTranslate(0f, sampleH.toFloat())
            android.graphics.Canvas(upright).drawBitmap(raw, flipMatrix, null)
            tracker.submit(upright, owned = false)
        } finally {
            restoreMainTarget()
        }
    }

    /** Re-focuses the framebuffer/viewport that drawFrame's real draw must land in. Idempotent —
     *  safe to call whether or not maybeSample actually changed the binding. */
    private fun restoreMainTarget() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mainFbo)
        GLES20.glViewport(mainViewport[0], mainViewport[1], mainViewport[2], mainViewport[3])
    }

    /** Regions arrive in top-left normalized coords; the shader works bottom-left. */
    private fun setRegions(regions: List<NormRegion>) {
        for (i in 0 until MAX_REGIONS) {
            val r = regions.getOrNull(i)
            program.setFloatsUniform("uR$i",
                if (r == null) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf(r.cx, 1f - r.cy, r.rx, r.ry))
        }
    }

    override fun release() {
        super.release()
        try { program.delete(); releaseSample() } catch (_: GlUtil.GlException) {}
    }

    private fun releaseSample() {
        if (sampleFbo != -1) GlUtil.deleteFbo(sampleFbo)
        if (sampleTex != -1) GlUtil.deleteTexture(sampleTex)
        sampleFbo = -1; sampleTex = -1
        // Not recycled here: the tracker may still be reading the last one. Dropping the
        // references is enough (GC frees them); new ones are made at the new size.
        rawSample = null; uprightSample = null
    }

    companion object {
        const val MAX_REGIONS = 8
        const val SAMPLE_WIDTH = 384
        const val DETECT_EVERY_MS = 140L

        private const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoords;
            void main() {
              gl_Position = aFramePosition;
              vTexCoords = (aFramePosition.xy + 1.0) * 0.5;
            }
        """

        // Unrolled region uniforms (uR0..uR7): array uniforms aren't reliably settable via GlProgram.
        private const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec4 uR0; uniform vec4 uR1; uniform vec4 uR2; uniform vec4 uR3;
            uniform vec4 uR4; uniform vec4 uR5; uniform vec4 uR6; uniform vec4 uR7;
            uniform float uMode;      // -1 passthrough, 0 blur, 1 pixelate, 2 solid
            uniform float uStrength;  // 0..1
            uniform float uAspect;    // width / height
            uniform float uShape;     // 2 = ellipse (face), 6 = rounded rectangle (body)
            varying vec2 vTexCoords;

            float cover(vec4 r, vec2 p) {
              if (r.z <= 0.0) return 0.0;
              vec2 d = abs((p - r.xy) / r.zw);
              float e = pow(d.x, uShape) + pow(d.y, uShape);
              return 1.0 - smoothstep(0.80, 1.0, e);       // feathered edge
            }

            vec4 blurAt(vec2 p) {
              float radius = mix(0.012, 0.06, uStrength);
              vec2 scale = vec2(radius / uAspect, radius);
              vec4 acc = texture2D(uTexSampler, p);
              float n = 1.0;
              // 24-tap golden-angle spiral: smooth, single pass
              for (int i = 1; i <= 24; i++) {
                float fi = float(i);
                float a = fi * 2.39996;
                float rr = sqrt(fi / 24.0);
                acc += texture2D(uTexSampler, p + vec2(cos(a), sin(a)) * rr * scale);
                n += 1.0;
              }
              return acc / n;
            }

            vec4 pixelAt(vec2 p) {
              float cells = mix(40.0, 12.0, uStrength);     // blocks across the frame height
              vec2 grid = vec2(cells * uAspect, cells);
              return texture2D(uTexSampler, (floor(p * grid) + 0.5) / grid);
            }

            void main() {
              vec4 src = texture2D(uTexSampler, vTexCoords);
              if (uMode < 0.0) { gl_FragColor = src; return; }
              float c = max(max(max(cover(uR0, vTexCoords), cover(uR1, vTexCoords)), max(cover(uR2, vTexCoords), cover(uR3, vTexCoords))),
                            max(max(cover(uR4, vTexCoords), cover(uR5, vTexCoords)), max(cover(uR6, vTexCoords), cover(uR7, vTexCoords))));
              if (c <= 0.0) { gl_FragColor = src; return; }
              vec4 hidden;
              if (uMode < 0.5) hidden = blurAt(vTexCoords);
              else if (uMode < 1.5) hidden = pixelAt(vTexCoords);
              else hidden = vec4(0.16, 0.16, 0.16, 1.0);
              gl_FragColor = mix(src, hidden, c);
            }
        """
    }
}
