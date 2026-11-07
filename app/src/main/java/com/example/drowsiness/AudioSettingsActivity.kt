package com.example.drowsiness

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsiness.audio.AlertManager
import com.example.drowsiness.audio.AudioSettingsManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class AudioSettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: AudioSettingsManager
    private lateinit var alertManager: AlertManager

    private lateinit var tvWarningFileName: TextView
    private lateinit var tvCriticalFileName: TextView
    private lateinit var btnPlayWarning: MaterialButton
    private lateinit var btnPlayCritical: MaterialButton
    private lateinit var switchVibration: SwitchMaterial

    private var isPlayingWarning = false
    private var isPlayingCritical = false

    private val selectWarningLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            settingsManager.saveWarningAudio(it)
            updateUi()
        }
    }

    private val selectCriticalLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            settingsManager.saveCriticalAudio(it)
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_settings)

        settingsManager = AudioSettingsManager(this)
        alertManager = AlertManager(this)

        initViews()
        updateUi()
    }

    private fun initViews() {
        tvWarningFileName = findViewById(R.id.tvWarningFileName)
        tvCriticalFileName = findViewById(R.id.tvCriticalFileName)
        btnPlayWarning = findViewById(R.id.btnPlayWarning)
        btnPlayCritical = findViewById(R.id.btnPlayCritical)
        switchVibration = findViewById(R.id.switchVibration)

        findViewById<MaterialButton>(R.id.btnSelectWarning).setOnClickListener {
            stopAllPlayback()
            selectWarningLauncher.launch(arrayOf("audio/*"))
        }

        findViewById<MaterialButton>(R.id.btnResetWarning).setOnClickListener {
            stopAllPlayback()
            settingsManager.resetWarningAudio()
            updateUi()
        }

        btnPlayWarning.setOnClickListener {
            if (isPlayingWarning) {
                stopAllPlayback()
            } else {
                stopAllPlayback()
                isPlayingWarning = true
                btnPlayWarning.text = "Stop"
                alertManager.previewAudio(settingsManager.warningAudioUri) {
                    runOnUiThread {
                        isPlayingWarning = false
                        btnPlayWarning.text = "Play"
                    }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnSelectCritical).setOnClickListener {
            stopAllPlayback()
            selectCriticalLauncher.launch(arrayOf("audio/*"))
        }

        findViewById<MaterialButton>(R.id.btnResetCritical).setOnClickListener {
            stopAllPlayback()
            settingsManager.resetCriticalAudio()
            updateUi()
        }

        btnPlayCritical.setOnClickListener {
            if (isPlayingCritical) {
                stopAllPlayback()
            } else {
                stopAllPlayback()
                isPlayingCritical = true
                btnPlayCritical.text = "Stop"
                alertManager.previewAudio(settingsManager.criticalAudioUri) {
                    runOnUiThread {
                        isPlayingCritical = false
                        btnPlayCritical.text = "Play"
                    }
                }
            }
        }

        switchVibration.isChecked = settingsManager.isVibrationEnabled
        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isVibrationEnabled = isChecked
        }

        findViewById<MaterialButton>(R.id.btnDone).setOnClickListener {
            stopAllPlayback()
            finish()
        }
    }

    private fun updateUi() {
        tvWarningFileName.text = settingsManager.warningAudioDisplayName
        tvCriticalFileName.text = settingsManager.criticalAudioDisplayName
        switchVibration.isChecked = settingsManager.isVibrationEnabled
    }

    private fun stopAllPlayback() {
        alertManager.stop()
        isPlayingWarning = false
        isPlayingCritical = false
        btnPlayWarning.text = "Play"
        btnPlayCritical.text = "Play"
    }

    override fun onPause() {
        stopAllPlayback()
        super.onPause()
    }

    override fun onDestroy() {
        stopAllPlayback()
        alertManager.close()
        super.onDestroy()
    }
}
