package com.example.drowsiness.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.example.drowsiness.detection.EyeAspectRatioCalculator
import com.example.drowsiness.detection.HeadPoseEstimator
import com.example.drowsiness.detection.MouthAspectRatioCalculator
import com.example.drowsiness.detection.Point2D

/**
 * Owns the MediaPipe task and extracts comprehensive facial metrics:
 * independent left/right EAR, combined EAR, mouth aspect ratio (MAR) for yawning,
 * and vertical head pitch for nodding.
 */
class FaceLandmarkerHelper(context: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker
    private val poseEstimator = HeadPoseEstimator()

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

    data class Observation(
        val ear: Double?,
        val faceDetected: Boolean,
        val leftEar: Double?,
        val rightEar: Double?,
        val mouthAspectRatio: Double? = null,
        val mouthMetrics: MouthAspectRatioCalculator.MouthMetrics? = null,
        val headPitchDegrees: Double? = null,
        val nodEventCount: Int = 0
    )

    fun detect(bitmap: Bitmap, timestampMs: Long): Observation {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: FaceLandmarkerResult = landmarker.detectForVideo(mpImage, timestampMs)
        val face = result.faceLandmarks().firstOrNull() ?: run {
            poseEstimator.suspendTracking()
            return Observation(null, false, null, null, nodEventCount = poseEstimator.totalNodCount)
        }

        val w = bitmap.width
        val h = bitmap.height

        // Independent Left & Right EAR
        val left = earFor(face, EyeAspectRatioCalculator.LEFT_EYE_INDICES, w, h)
        val right = earFor(face, EyeAspectRatioCalculator.RIGHT_EYE_INDICES, w, h)
        val combined = when {
            left != null && right != null -> (left + right) / 2.0
            left != null -> left
            right != null -> right
            else -> null
        }

        // Mouth Aspect Ratio (MAR) for Yawning
        val mouthMetrics = calculateMouthMetrics(face, w, h)
        val mar = mouthMetrics?.mar

        // Head Pose Pitch for Nodding
        val pitch = estimateHeadPitch(face, w, h, timestampMs)

        return Observation(
            ear = combined,
            faceDetected = combined != null || face.isNotEmpty(),
            leftEar = left,
            rightEar = right,
            mouthAspectRatio = mar,
            mouthMetrics = mouthMetrics,
            headPitchDegrees = pitch,
            nodEventCount = poseEstimator.totalNodCount
        )
    }

    private fun earFor(
        landmarks: List<NormalizedLandmark>,
        indices: IntArray,
        width: Int,
        height: Int
    ): Double? {
        if (indices.any { it !in landmarks.indices }) return null
        val points = indices.map { index ->
            val p = landmarks[index]
            Point2D(p.x().toDouble() * width, p.y().toDouble() * height)
        }
        return EyeAspectRatioCalculator.calculate(points)
    }

    private fun calculateMouthMetrics(landmarks: List<NormalizedLandmark>, width: Int, height: Int): MouthAspectRatioCalculator.MouthMetrics? {
        if (MouthAspectRatioCalculator.ALL_MOUTH_INDICES.any { it !in landmarks.indices }) return null

        fun pt(index: Int): Point2D {
            val p = landmarks[index]
            return Point2D(p.x().toDouble() * width, p.y().toDouble() * height)
        }

        return MouthAspectRatioCalculator.computeMetrics(
            leftCorner = pt(MouthAspectRatioCalculator.CORNER_LEFT),
            rightCorner = pt(MouthAspectRatioCalculator.CORNER_RIGHT),
            upperCenter = pt(MouthAspectRatioCalculator.UPPER_LIP),
            lowerCenter = pt(MouthAspectRatioCalculator.LOWER_LIP),
            upperLeft = pt(MouthAspectRatioCalculator.UPPER_LEFT),
            lowerLeft = pt(MouthAspectRatioCalculator.LOWER_LEFT),
            upperRight = pt(MouthAspectRatioCalculator.UPPER_RIGHT),
            lowerRight = pt(MouthAspectRatioCalculator.LOWER_RIGHT)
        )
    }

    private fun calculateMar(landmarks: List<NormalizedLandmark>, width: Int, height: Int): Double? =
        calculateMouthMetrics(landmarks, width, height)?.mar

    private fun estimateHeadPitch(
        landmarks: List<NormalizedLandmark>,
        width: Int,
        height: Int,
        timestampMs: Long
    ): Double? {
        if (HeadPoseEstimator.POSE_INDICES.any { it !in landmarks.indices }) {
            poseEstimator.suspendTracking()
            return null
        }

        fun pt(index: Int): Point2D {
            val p = landmarks[index]
            return Point2D(p.x().toDouble() * width, p.y().toDouble() * height)
        }

        val res = poseEstimator.estimate(
            forehead = pt(HeadPoseEstimator.FOREHEAD),
            noseTip = pt(HeadPoseEstimator.NOSE_TIP),
            chin = pt(HeadPoseEstimator.CHIN),
            leftEye = pt(HeadPoseEstimator.LEFT_EYE),
            rightEye = pt(HeadPoseEstimator.RIGHT_EYE),
            timestampMs = timestampMs
        )
        return res.pitchDegrees
    }

    override fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "face_landmarker.task"
    }
}
