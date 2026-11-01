package com.youtubly.app.blur

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

/** Bundled ML Kit detector: fully offline, no Play Services required. */
class FaceDetector {
    private val clients = HashMap<Int, com.google.mlkit.vision.face.FaceDetector>()

    private fun client(minFacePct: Int) = synchronized(clients) {
        clients.getOrPut(minFacePct) {
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(minFacePct / 100f)
                    .build()
            )
        }
    }

    suspend fun detect(bitmap: Bitmap, minFacePct: Int): List<Rect> =
        client(minFacePct).process(InputImage.fromBitmap(bitmap, 0)).await().map { it.boundingBox }

    fun close() = synchronized(clients) { clients.values.forEach { it.close() }; clients.clear() }
}
