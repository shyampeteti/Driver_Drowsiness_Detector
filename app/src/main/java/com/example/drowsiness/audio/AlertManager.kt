package com.example.drowsiness.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.drowsiness.detection.AlertLevel

/**
 * Intelligent escalating alert manager.
 * Supports custom audio playback with fallback to ToneGenerator,
 * vibration feedback, and anti-overlap audio control.
 */
class AlertManager(private val context: Context) : AutoCloseable {
    val settingsManager = AudioSettingsManager(context)

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var isTonePlaying = false
    private var isPlayingCustom = false
    private var activeLevel = AlertLevel.NONE

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    @Synchronized
    fun playAlert(level: AlertLevel) {
        when (level) {
            AlertLevel.CRITICAL -> {
                if (activeLevel == AlertLevel.CRITICAL && isAudioPlaying()) {
                    if (settingsManager.isVibrationEnabled) triggerVibration()
                    return
                }
                stopCurrentAudio()
                activeLevel = AlertLevel.CRITICAL
                val criticalUri = settingsManager.criticalAudioUri
                val played = criticalUri?.let { playCustomUri(it) } ?: false
                if (!played) {
                    playDefaultTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
                }
                if (settingsManager.isVibrationEnabled) {
                    triggerVibration()
                }
            }
            AlertLevel.WARNING -> {
                // If critical is currently active, do not downgrade to warning audio
                if (activeLevel == AlertLevel.CRITICAL && isAudioPlaying()) return
                if (activeLevel == AlertLevel.WARNING && isAudioPlaying()) return

                stopCurrentAudio()
                activeLevel = AlertLevel.WARNING
                val warningUri = settingsManager.warningAudioUri
                val played = warningUri?.let { playCustomUri(it) } ?: false
                if (!played) {
                    playDefaultTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
                }
            }
            AlertLevel.NONE -> {
                stop()
            }
        }
    }

    /**
     * Preview custom audio from settings screen.
     */
    @Synchronized
    fun previewAudio(uri: Uri?, onCompletion: (() -> Unit)? = null) {
        stopCurrentAudio()
        if (uri == null) {
            playDefaultTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
            onCompletion?.invoke()
            return
        }
        val success = playCustomUri(uri, onCompletion)
        if (!success) {
            playDefaultTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
            onCompletion?.invoke()
        }
    }

    private fun playCustomUri(uri: Uri, onCompletion: (() -> Unit)? = null): Boolean {
        return try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = onCompletion == null
                setOnCompletionListener { mp ->
                    mp.release()
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                        isPlayingCustom = false
                        activeLevel = AlertLevel.NONE
                    }
                    onCompletion?.invoke()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                        isPlayingCustom = false
                    }
                    false
                }
                prepare()
                start()
            }
            mediaPlayer = player
            isPlayingCustom = true
            true
        } catch (_: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
            isPlayingCustom = false
            false
        }
    }

    private fun playDefaultTone(toneType: Int) {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 95)
            }
            isTonePlaying = toneGenerator?.startTone(toneType) == true
        } catch (_: Exception) { }
    }

    private fun triggerVibration() {
        try {
            vibrator?.let { v ->
                if (!v.hasVibrator()) return
                val timings = longArrayOf(0, 400, 200, 400, 200, 400)
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                    v.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(timings, -1)
                }
            }
        } catch (_: Exception) { }
    }

    private fun isAudioPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true || isTonePlaying
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun stop() {
        stopCurrentAudio()
        activeLevel = AlertLevel.NONE
        try { vibrator?.cancel() } catch (_: Exception) { }
    }

    private fun stopCurrentAudio() {
        try {
            toneGenerator?.stopTone()
            isTonePlaying = false
        } catch (_: Exception) { }

        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        } catch (_: Exception) { }
        mediaPlayer = null
        isPlayingCustom = false
    }

    @Synchronized
    override fun close() {
        stop()
        try {
            toneGenerator?.release()
        } catch (_: Exception) { }
        toneGenerator = null
    }
}
