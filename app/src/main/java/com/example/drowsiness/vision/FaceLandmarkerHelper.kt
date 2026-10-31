package com.example.drowsiness.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.example.drowsiness.detection.EyeAspectRatioCalculator
import com.example.drowsiness.detection.Point2D

/** Owns the MediaPipe task and extracts a combined EAR from the first detected face. */
class FaceLandmarkerHelper(context: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker

    init {
        val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    data class Observation(val ear: Double?, val faceDetected: Boolean, val leftEar: Double?, val rightEar: Double?)

    fun detect(bitmap: Bitmap, timestampMs: Long): Observation {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: FaceLandmarkerResult = landmarker.detectForVideo(mpImage, timestampMs)
        val face = result.faceLandmarks().firstOrNull() ?: return Observation(null, false, null, null)
        // MediaPipe Face Mesh topology: standard six-point EAR sets.
        // Left: 33,160,158,133,153,144. Right: 362,385,387,263,373,380.
        val left = earFor(face, intArrayOf(33, 160, 158, 133, 153, 144), bitmap.width, bitmap.height)
        val right = earFor(face, intArrayOf(362, 385, 387, 263, 373, 380), bitmap.width, bitmap.height)
        val combined = when {
            left != null && right != null -> (left + right) / 2.0
            left != null -> left
            right != null -> right
            else -> null
        }
        return Observation(combined, combined != null, left, right)
    }

    private fun earFor(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        indices: IntArray,
        width: Int,
        height: Int
    ): Double? {
        if (indices.any { it !in landmarks.indices }) return null
        val points = indices.map { index ->
            val point = landmarks[index]
            Point2D(point.x().toDouble() * width, point.y().toDouble() * height)
        }
        return EyeAspectRatioCalculator.calculate(points)
    }

    override fun close() = landmarker.close()

    companion object { const val MODEL_ASSET = "face_landmarker.task" }
}
