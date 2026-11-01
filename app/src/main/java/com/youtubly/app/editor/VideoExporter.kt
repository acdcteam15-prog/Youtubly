package com.youtubly.app.editor

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.youtubly.app.domain.editor.ClipSpec
import com.youtubly.app.domain.editor.ConvertRules
import com.youtubly.app.domain.editor.ExportMath
import com.youtubly.app.domain.editor.ExportSpec
import com.youtubly.app.domain.editor.OutputMath
import com.youtubly.app.domain.editor.TimeRange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when the user cancelled the job from the UI (as opposed to the worker being stopped). */
class ExportCancelledByUser : Exception("Cancelled")

/** Export failure with a message fit for the job list. */
class ExportFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs an [ExportSpec] with Media3 Transformer and writes an MP4 (H.264 video, AAC audio).
 *
 * Fast path ([ExportSpec.isSimpleTrim]): one file, one range, no compression:
 *
 * - MP4/H.264/AAC sources are not re-encoded: trim optimization re-encodes only the few
 *   frames up to the first keyframe after the cut, and copies the rest, so the cut is
 *   frame-accurate and a 500 MB file takes seconds, not minutes. Where that isn't possible,
 *   Transformer falls back to a normal export by itself.
 * - WebM (VP9/Opus) sources are transcoded to H.264/AAC so the result plays everywhere.
 *
 * Everything else (cuts, merges, resolution/bitrate) is one Composition: a single sequence of
 * clipped items, re-encoded so every cut lands on the exact frame. Clips whose size differs
 * from the output are scaled to fit (letterboxed), so videos of different sizes can be merged.
 *
 * Transformer must be created and driven from one Looper thread; everything here runs on
 * Main. The heavy lifting happens on Transformer's own internal threads.
 */
@OptIn(UnstableApi::class)
@Singleton
class VideoExporter @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun export(
        spec: ExportSpec,
        output: File,
        onProgress: suspend (Int) -> Unit,
        shouldCancel: suspend () -> Boolean
    ) = withContext(Dispatchers.Main) {
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) throw ExportFailed("Couldn't prepare the output file")

        val done = CompletableDeferred<Unit>()
        val builder = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
        // Trim optimization works from video keyframes; audio-only files take the normal path
        // (cheap anyway, audio isn't expensive to re-encode).
        // It also only applies to MP4 input, so other formats (WebM…) skip straight to a normal export.
        if (spec.isSimpleTrim && !spec.audioOnly && ConvertRules.extension(spec.clips.first().path) == "mp4") {
            builder.experimentalSetTrimOptimizationEnabled(true)
        }
        spec.output.videoBitrateKbps?.takeIf { !spec.audioOnly }?.let { kbps ->
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(kbps * 1000).build())
                    .build()
            )
        }
        val transformer = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(ExportFailed(readable(exportException), exportException))
                }
            })
            .build()

        if (spec.isSimpleTrim) {
            val clip = spec.clips.first()
            transformer.start(mediaItem(clip, clip.keep.first()), output.absolutePath)
        } else {
            transformer.start(composition(spec), output.absolutePath)
        }
        val watcher = launch {
            val holder = ProgressHolder()
            var last = -1
            while (isActive && !done.isCompleted) {
                if (shouldCancel()) {
                    transformer.cancel()
                    done.completeExceptionally(ExportCancelledByUser())
                    break
                }
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val p = ExportMath.exportPercent(holder.progress)
                    if (p != last) { last = p; onProgress(p) }
                }
                delay(500)
            }
        }
        try {
            done.await()
        } catch (t: Throwable) {
            // Worker stopped (CancellationException) or export error: make sure the encoder
            // is released and no half-written file is left behind. cancel() is a no-op once
            // Transformer has already finished or failed.
            transformer.cancel()
            output.delete()
            throw t
        } finally {
            watcher.cancel()
        }
    }

    private fun mediaItem(clip: ClipSpec, range: TimeRange): MediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(range.startMs)
            // Leaving the end unset when the range runs to the end avoids clipping a few ms
            // off a video whose real duration differs slightly from the container's.
            .apply { if (range.endMs < clip.durationMs) setEndPositionMs(range.endMs) }
            .build()
        return MediaItem.Builder()
            .setUri(Uri.fromFile(File(clip.path)))
            .setClippingConfiguration(clipping)
            .build()
    }

    private fun composition(spec: ExportSpec): Composition {
        val first = spec.clips.first()
        val target = OutputMath.targetSize(first.width, first.height, spec.output.maxHeight)
        val items = spec.clips.flatMap { clip ->
            // Scale only clips that don't already match the output size.
            val scale = !spec.audioOnly && target != null && (clip.width != target.first || clip.height != target.second)
            clip.keep.map { range ->
                EditedMediaItem.Builder(mediaItem(clip, range))
                    // Audio export of a video file ("Extract audio"): keep only the sound.
                    .setRemoveVideo(spec.audioOnly)
                    .apply {
                        if (scale && target != null) {
                            setEffects(
                                Effects(
                                    /* audioProcessors = */ emptyList(),
                                    /* videoEffects = */ listOf<Effect>(Presentation.createForWidthAndHeight(target.first, target.second, Presentation.LAYOUT_SCALE_TO_FIT))
                                )
                            )
                        }
                    }
                    .build()
            }
        }
        // Media3 1.11: the old Builder(List) + Composition.experimentalSetForceAudioTrack(true) combo
        // throws "trackTypes must only contain TRACK_TYPE_AUDIO and/or TRACK_TYPE_VIDEO" at build().
        // Declaring the track types on the sequence does the same job: AUDIO is always present, so a
        // clip without sound gets silence instead of failing the merge; VIDEO only when we keep video.
        val trackTypes = if (spec.audioOnly) setOf(C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        return Composition.Builder(EditedMediaItemSequence.Builder(trackTypes).addItems(items).build())
            .build()
    }

    private fun readable(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The saved video no longer exists"
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This phone can't decode this video's format"
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "This phone's encoder couldn't handle this video"
        ExportException.ERROR_CODE_MUXING_FAILED -> "Couldn't write the MP4 file"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Export failed (code ${e.errorCode})"
    }

}
