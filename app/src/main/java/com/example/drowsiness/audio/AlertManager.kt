package com.example.drowsiness.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/** Single tone generator prevents overlapping players; the detector owns cooldown policy. */
class AlertManager(context: Context) : AutoCloseable {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var toneGenerator: ToneGenerator? = null

    @Synchronized fun playWarning() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 85)
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
    }

    @Synchronized fun stop() { toneGenerator?.stopTone() }

    @Synchronized override fun close() {
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null
    }
}
