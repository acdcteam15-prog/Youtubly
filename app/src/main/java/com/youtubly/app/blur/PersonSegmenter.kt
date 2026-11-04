package com.youtubly.app.blur

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await

/** Bundled ML Kit selfie segmentation (offline, no Play Services): which pixels belong to a person. */
class PersonSegmenter {
    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    suspend fun mask(bitmap: Bitmap): PersonMask {
        val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        val buffer = result.buffer
        val w = result.width
        val h = result.height
        buffer.rewind()
        return PersonMask(FloatArray(w * h) { buffer.float }, w, h)
    }

    fun close() = segmenter.close()
}
