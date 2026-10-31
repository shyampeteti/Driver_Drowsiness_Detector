package com.example.drowsiness

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.example.drowsiness.detection.DetectionSnapshot
import com.example.drowsiness.detection.DetectionState
import com.example.drowsiness.detection.DrowsinessDetector
import com.example.drowsiness.vision.FaceLandmarkerHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var detector: DrowsinessDetector
    private lateinit var alertManager: AlertManager
    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: FaceLandmarkerHelper? = null
    private var analysisExecutor: ExecutorService? = null
    private val processingFrame = AtomicBoolean(false)
    private var monitoring = false
    private var lastInferenceMs = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMonitoring() else updateStatus("Camera permission denied. Grant camera access to monitor.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detector = DrowsinessDetector()
        alertManager = AlertManager(this)
        buildUi()
        updateStatus("Ready to start")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(0xFFF4F7FA.toInt())
        }
        val title = TextView(this).apply {
            text = "Driver Drowsiness Detection"
            textSize = 23f
            setTextColor(0xFF102A43.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "On-device eye-closure monitoring · Camera frames stay on this device"
            textSize = 14f
            setTextColor(0xFF486581.toInt())
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        statusView = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF102A43.toInt())
            setPadding(0, dp(12), 0, dp(6))
        }
        diagnosticsView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF486581.toInt())
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        startButton = Button(this).apply { text = "Start Monitoring"; setOnClickListener { requestOrStart() } }
        stopButton = Button(this).apply { text = "Stop Monitoring"; isEnabled = false; setOnClickListener { stopMonitoring() } }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title)
        root.addView(subtitle)
        root.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)).apply { topMargin = dp(12) })
        root.addView(statusView)
        root.addView(diagnosticsView)
        root.addView(buttons)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
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
        startButton.isEnabled = false
        stopButton.isEnabled = true
        updateStatus("Initializing model…")
        analysisExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor?.execute {
            try {
                landmarker = FaceLandmarkerHelper(applicationContext)
                runOnUiThread { updateStatus("Starting camera…") }
                val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
                providerFuture.addListener({
                    try {
                        if (!monitoring) return@addListener
                        cameraProvider = providerFuture.get()
                        bindCamera()
                        val snapshot = detector.start()
                        runOnUiThread { render(snapshot) }
                    } catch (e: Exception) {
                        onPipelineError("Camera initialization failed: ${e.message ?: "unknown error"}")
                    }
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                onPipelineError("Model initialization failed. Check that face_landmarker.task is bundled. ${e.message ?: ""}")
            }
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera provider unavailable")
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))
            .build()
        val executor = analysisExecutor ?: throw IllegalStateException("Analysis executor unavailable")
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastInferenceMs < 100L || !processingFrame.compareAndSet(false, true)) return@setAnalyzer
                lastInferenceMs = now
                val raw = YuvBitmapConverter.convert(image) ?: return@setAnalyzer
                val rotated = rotateBitmap(raw, image.imageInfo.rotationDegrees)
                if (rotated !== raw) raw.recycle()
                val observation = landmarker?.detect(rotated, now)
                rotated.recycle()
                val snapshot = if (observation == null) detector.faceLost(now) else detector.observe(observation.ear, now, observation.faceDetected)
                if (snapshot.alarmRequested) alertManager.playWarning()
                if (snapshot.state != DetectionState.DROWSINESS_DETECTED) alertManager.stop()
                runOnUiThread { if (monitoring) render(snapshot) }
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
        statusView.text = snapshot.statusMessage
        val perclosText = snapshot.perclos?.let { "${(it * 100).toInt()}%" } ?: "Not enough valid observations"
        diagnosticsView.text = "State: ${snapshot.state}\nEye state: ${snapshot.eyeState}\nEAR: ${snapshot.ear?.let { "%.3f".format(it) } ?: "—"}\nClosure: ${snapshot.closureDurationMs} ms\nPERCLOS-style: $perclosText"
        if (snapshot.state == DetectionState.DROWSINESS_DETECTED) statusView.setTextColor(0xFFB00020.toInt())
        else statusView.setTextColor(0xFF102A43.toInt())
    }

    private fun updateStatus(message: String) { statusView.text = message }

    private fun onPipelineError(message: String) {
        runOnUiThread {
            updateStatus(message)
            detector.fail()
            monitoring = false
            startButton.isEnabled = true
            stopButton.isEnabled = false
            releasePipeline()
        }
    }

    private fun stopMonitoring() {
        if (!monitoring && analysisExecutor == null) return
        monitoring = false
        val snapshot = detector.stop()
        alertManager.stop()
        releasePipeline()
        startButton.isEnabled = true
        stopButton.isEnabled = false
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
