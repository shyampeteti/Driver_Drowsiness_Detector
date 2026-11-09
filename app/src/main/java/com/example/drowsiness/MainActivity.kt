package com.example.drowsiness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.drowsiness.audio.AlertManager
import com.example.drowsiness.camera.YuvBitmapConverter
import com.example.drowsiness.data.DrivingSession
import com.example.drowsiness.data.SessionRepository
import com.example.drowsiness.detection.AlertLevel
import com.example.drowsiness.detection.DetectionSnapshot
import com.example.drowsiness.detection.DetectionState
import com.example.drowsiness.detection.DrowsinessDetector
import com.example.drowsiness.detection.DurationCategory
import com.example.drowsiness.vision.FaceLandmarkerHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var tvStateBadge: TextView
    private lateinit var viewFaceIndicatorDot: View
    private lateinit var tvFaceStatusText: TextView
    private lateinit var tvCalibrationBadge: TextView
    private lateinit var cardAlertBanner: MaterialCardView
    private lateinit var tvBannerStatus: TextView
    private lateinit var tvBannerAdvice: TextView
    private lateinit var tvEarValue: TextView
    private lateinit var tvEarBreakdown: TextView
    private lateinit var tvClosureDuration: TextView
    private lateinit var tvDurationCategory: TextView
    private lateinit var tvPerclosValue: TextView
    private lateinit var tvPerclosStatus: TextView
    private lateinit var tvScoreValue: TextView
    private lateinit var tvEventsSummary: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnAudioSettings: MaterialButton
    private lateinit var btnSessionHistory: MaterialButton
    private lateinit var btnCalibrate: MaterialButton

    // Components
    private lateinit var detector: DrowsinessDetector
    private lateinit var alertManager: AlertManager
    private lateinit var sessionRepository: SessionRepository

    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: FaceLandmarkerHelper? = null
    private var analysisExecutor: ExecutorService? = null
    private val processingFrame = AtomicBoolean(false)
    private var monitoring = false
    private var lastInferenceMs = 0L

    // Session Telemetry Tracking
    private var sessionStartTimeMs: Long = 0L
    private var lastStateTimestampMs: Long = 0L
    private var timeNormalMs: Long = 0L
    private var timeWarningMs: Long = 0L
    private var timeCriticalMs: Long = 0L
    private var timeFaceLostMs: Long = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startMonitoring()
        } else {
            showStatusMessage("Camera permission denied. Grant camera access to monitor.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        detector = DrowsinessDetector()
        alertManager = AlertManager(this)
        sessionRepository = SessionRepository(this)

        initViews()
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        tvStateBadge = findViewById(R.id.tvStateBadge)
        viewFaceIndicatorDot = findViewById(R.id.viewFaceIndicatorDot)
        tvFaceStatusText = findViewById(R.id.tvFaceStatusText)
        tvCalibrationBadge = findViewById(R.id.tvCalibrationBadge)
        cardAlertBanner = findViewById(R.id.cardAlertBanner)
        tvBannerStatus = findViewById(R.id.tvBannerStatus)
        tvBannerAdvice = findViewById(R.id.tvBannerAdvice)
        tvEarValue = findViewById(R.id.tvEarValue)
        tvEarBreakdown = findViewById(R.id.tvEarBreakdown)
        tvClosureDuration = findViewById(R.id.tvClosureDuration)
        tvDurationCategory = findViewById(R.id.tvDurationCategory)
        tvPerclosValue = findViewById(R.id.tvPerclosValue)
        tvPerclosStatus = findViewById(R.id.tvPerclosStatus)
        tvScoreValue = findViewById(R.id.tvScoreValue)
        tvEventsSummary = findViewById(R.id.tvEventsSummary)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnAudioSettings = findViewById(R.id.btnAudioSettings)
        btnSessionHistory = findViewById(R.id.btnSessionHistory)
        btnCalibrate = findViewById(R.id.btnCalibrate)

        btnStart.setOnClickListener { requestOrStart() }
        btnStop.setOnClickListener { stopMonitoring() }

        btnAudioSettings.setOnClickListener {
            startActivity(Intent(this, AudioSettingsActivity::class.java))
        }

        btnSessionHistory.setOnClickListener {
            startActivity(Intent(this, SessionHistoryActivity::class.java))
        }

        btnCalibrate.setOnClickListener {
            if (monitoring) {
                detector.startCalibration(SystemClock.elapsedRealtime())
                Toast.makeText(this, "Calibrating baseline... Look ahead at road", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Start monitoring first to calibrate", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestOrStart() {
        if (monitoring) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startMonitoring()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        btnCalibrate.isEnabled = true

        // Reset session telemetry
        sessionStartTimeMs = System.currentTimeMillis()
        lastStateTimestampMs = SystemClock.elapsedRealtime()
        timeNormalMs = 0L
        timeWarningMs = 0L
        timeCriticalMs = 0L
        timeFaceLostMs = 0L

        showStatusMessage("Initializing AI model…")
        analysisExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor?.execute {
            try {
                landmarker = FaceLandmarkerHelper(applicationContext)
                runOnUiThread { showStatusMessage("Binding automotive camera…") }
                val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
                providerFuture.addListener({
                    try {
                        if (!monitoring) return@addListener
                        cameraProvider = providerFuture.get()
                        bindCamera()
                        val snapshot = detector.start(enableCalibration = true, nowMs = SystemClock.elapsedRealtime())
                        runOnUiThread { render(snapshot) }
                    } catch (e: Exception) {
                        onPipelineError("Camera initialization failed: ${e.message ?: "unknown error"}")
                    }
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                onPipelineError("Model initialization failed. Check face_landmarker.task: ${e.message ?: ""}")
            }
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera provider unavailable")
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val executor = analysisExecutor ?: throw IllegalStateException("Analysis executor unavailable")

        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                // Process at ~10 FPS for high responsiveness with low battery/thermal impact
                if (now - lastInferenceMs < 100L || !processingFrame.compareAndSet(false, true)) {
                    return@setAnalyzer
                }
                lastInferenceMs = now

                val raw = YuvBitmapConverter.convert(image) ?: return@setAnalyzer
                val rotated = rotateBitmap(raw, image.imageInfo.rotationDegrees)
                if (rotated !== raw) raw.recycle()

                val observation = landmarker?.detect(rotated, now)
                rotated.recycle()

                val snapshot = if (observation == null) {
                    detector.faceLost(now)
                } else {
                    detector.observe(
                        leftEar = observation.leftEar,
                        rightEar = observation.rightEar,
                        faceDetected = observation.faceDetected,
                        mouthAspectRatio = observation.mouthAspectRatio,
                        mouthMetrics = observation.mouthMetrics,
                        headPitchDegrees = observation.headPitchDegrees,
                        timestampMs = now
                    )
                }

                // Accumulate state duration for driving session analytics
                val delta = if (lastStateTimestampMs > 0L) now - lastStateTimestampMs else 0L
                lastStateTimestampMs = now
                when (snapshot.state) {
                    DetectionState.MONITORING -> timeNormalMs += delta
                    DetectionState.DROWSY -> timeWarningMs += delta
                    DetectionState.CRITICAL -> timeCriticalMs += delta
                    DetectionState.FACE_NOT_DETECTED -> timeFaceLostMs += delta
                    else -> {}
                }

                // Escalating Alert Sound & Vibration Execution
                if (snapshot.alarmRequested) {
                    alertManager.playAlert(snapshot.alertLevel)
                } else if (snapshot.alertLevel == AlertLevel.NONE) {
                    alertManager.stop()
                }

                runOnUiThread {
                    if (monitoring) render(snapshot)
                }
            } catch (e: Exception) {
                onPipelineError("Frame processing failed: ${e.message ?: "unknown error"}")
            } finally {
                processingFrame.set(false)
                image.close()
            }
        }

        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun render(snapshot: DetectionSnapshot) {
        // State Badge
        tvStateBadge.text = snapshot.state.name
        when (snapshot.state) {
            DetectionState.CRITICAL -> {
                tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.status_critical_red))
                tvStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_critical_red_bg)
            }
            DetectionState.DROWSY -> {
                tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber))
                tvStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_warning_amber_bg)
            }
            DetectionState.MONITORING -> {
                tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.status_normal_green))
                tvStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_normal_green_bg)
            }
            else -> {
                tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.status_neutral_grey))
                tvStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_neutral_grey_bg)
            }
        }

        // Face Indicator
        val faceDetected = snapshot.state != DetectionState.FACE_NOT_DETECTED && snapshot.state != DetectionState.STOPPED
        if (faceDetected) {
            viewFaceIndicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_normal_green)
            tvFaceStatusText.text = "Face Locked"
        } else {
            viewFaceIndicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_warning_amber)
            tvFaceStatusText.text = "Face Searching…"
        }

        // Calibration overlay badge
        if (snapshot.isCalibrating) {
            tvCalibrationBadge.visibility = View.VISIBLE
            tvCalibrationBadge.text = "Calibrating ${snapshot.calibrationProgressPercent}%"
        } else {
            tvCalibrationBadge.visibility = View.GONE
        }

        // Alert Banner
        tvBannerStatus.text = snapshot.statusMessage
        when (snapshot.state) {
            DetectionState.CRITICAL -> {
                cardAlertBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_critical_red_bg))
                tvBannerAdvice.text = "Pull over immediately to a safe location. Do not continue driving."
                tvBannerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_critical_red))
            }
            DetectionState.DROWSY -> {
                cardAlertBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_warning_amber_bg))
                tvBannerAdvice.text = "Frequent blinks / yawning observed. Stay attentive or plan a rest break."
                tvBannerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber))
            }
            DetectionState.FACE_NOT_DETECTED -> {
                cardAlertBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_neutral_grey_bg))
                tvBannerAdvice.text = "Position device so front camera has an unobstructed view of face."
                tvBannerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber))
            }
            else -> {
                cardAlertBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg))
                tvBannerAdvice.text = "System active · Real-time on-device safety monitoring"
                tvBannerStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }

        // EAR Metrics
        tvEarValue.text = snapshot.ear?.let { String.format(Locale.US, "%.3f", it) } ?: "—"
        val l = snapshot.leftEar?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
        val r = snapshot.rightEar?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
        tvEarBreakdown.text = "L: $l  R: $r (${snapshot.eyeState.name})"

        // Closure Duration
        tvClosureDuration.text = "${snapshot.closureDurationMs} ms"
        tvDurationCategory.text = snapshot.durationCategory.label
        when (snapshot.durationCategory) {
            DurationCategory.CRITICAL_SUSTAINED -> tvDurationCategory.setTextColor(ContextCompat.getColor(this, R.color.status_critical_red))
            DurationCategory.SERIOUS_WARNING -> tvDurationCategory.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber))
            DurationCategory.POTENTIAL_DROWSINESS -> tvDurationCategory.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber))
            else -> tvDurationCategory.setTextColor(ContextCompat.getColor(this, R.color.status_normal_green))
        }

        // PERCLOS
        tvPerclosValue.text = snapshot.perclos?.let { String.format(Locale.US, "%.1f%%", it * 100.0) } ?: "—"
        snapshot.perclos?.let {
            if (it >= 0.40) {
                tvPerclosStatus.text = "Elevated closure ratio!"
                tvPerclosStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_amber))
            } else {
                tvPerclosStatus.text = "Within safe limit (< 40%)"
                tvPerclosStatus.setTextColor(ContextCompat.getColor(this, R.color.status_normal_green))
            }
        } ?: run {
            tvPerclosStatus.text = "Collecting window observations…"
            tvPerclosStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Drowsiness Score & Event Summary
        tvScoreValue.text = String.format(Locale.US, "%.1f", snapshot.drowsinessScore)
        tvEventsSummary.text = "Warn: ${snapshot.warningEventsCount} · Crit: ${snapshot.criticalEventsCount} · Yawn: ${snapshot.yawnCount}"
    }

    private fun showStatusMessage(message: String) {
        tvBannerStatus.text = message
    }

    private fun onPipelineError(message: String) {
        runOnUiThread {
            showStatusMessage(message)
            detector.fail()
            monitoring = false
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            releasePipeline()
        }
    }

    private fun stopMonitoring() {
        if (!monitoring && analysisExecutor == null) return
        monitoring = false
        val snapshot = detector.stop()
        alertManager.stop()
        releasePipeline()

        btnStart.isEnabled = true
        btnStop.isEnabled = false

        // Record Completed Driving Session
        val endTimeMs = System.currentTimeMillis()
        val totalDurationMs = endTimeMs - sessionStartTimeMs
        if (totalDurationMs > 2_000L) { // Only save sessions longer than 2 seconds
            val session = DrivingSession(
                startTimeMs = sessionStartTimeMs,
                endTimeMs = endTimeMs,
                durationMs = totalDurationMs,
                warningEventsCount = detector.warningEventsCount,
                criticalEventsCount = detector.criticalEventsCount,
                longestClosureMs = detector.episodeTracker.longestClosureMs,
                averagePerclos = snapshot.perclos ?: 0.0,
                timeNormalMs = timeNormalMs,
                timeWarningMs = timeWarningMs,
                timeCriticalMs = timeCriticalMs,
                timeFaceLostMs = timeFaceLostMs,
                yawnCount = detector.yawnTracker.totalYawnCount
            )
            sessionRepository.insertSession(session)
        }

        render(snapshot)
    }

    private fun releasePipeline() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        try { landmarker?.close() } catch (_: Exception) { }
        landmarker = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        processingFrame.set(false)
    }

    override fun onStop() {
        if (monitoring) stopMonitoring()
        super.onStop()
    }

    override fun onDestroy() {
        releasePipeline()
        alertManager.close()
        super.onDestroy()
    }
}
