package com.example.drowsiness.audio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Manages custom user-selected alert audio URIs and preferences.
 * Uses persistent SAF permissions to retain file access across device and app reboots.
 */
class AudioSettingsManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "driver_audio_settings"
        private const val KEY_WARNING_URI = "key_warning_uri"
        private const val KEY_WARNING_NAME = "key_warning_name"
        private const val KEY_CRITICAL_URI = "key_critical_uri"
        private const val KEY_CRITICAL_NAME = "key_critical_name"
        private const val KEY_VIBRATION_ENABLED = "key_vibration_enabled"
    }

    var warningAudioUri: Uri?
        get() = prefs.getString(KEY_WARNING_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_WARNING_URI, value?.toString()).apply()

    var warningAudioDisplayName: String
        get() = prefs.getString(KEY_WARNING_NAME, "Built-in Alert Tone (CDMA Guard)") ?: "Built-in Alert Tone"
        set(value) = prefs.edit().putString(KEY_WARNING_NAME, value).apply()

    var criticalAudioUri: Uri?
        get() = prefs.getString(KEY_CRITICAL_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_CRITICAL_URI, value?.toString()).apply()

    var criticalAudioDisplayName: String
        get() = prefs.getString(KEY_CRITICAL_NAME, "Built-in Siren (Emergency Alarm)") ?: "Built-in Siren"
        set(value) = prefs.edit().putString(KEY_CRITICAL_NAME, value).apply()

    var isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    /**
     * Persists URI permission and saves file details for warning sound.
     */
    fun saveWarningAudio(uri: Uri) {
        takePersistablePermission(uri)
        warningAudioUri = uri
        warningAudioDisplayName = queryDisplayName(uri) ?: "Custom Warning Sound"
    }

    /**
     * Resets warning sound to default built-in tone.
     */
    fun resetWarningAudio() {
        warningAudioUri = null
        warningAudioDisplayName = "Built-in Alert Tone (CDMA Guard)"
    }

    /**
     * Persists URI permission and saves file details for critical alert sound.
     */
    fun saveCriticalAudio(uri: Uri) {
        takePersistablePermission(uri)
        criticalAudioUri = uri
        criticalAudioDisplayName = queryDisplayName(uri) ?: "Custom Critical Alert"
    }

    /**
     * Resets critical sound to default built-in siren.
     */
    fun resetCriticalAudio() {
        criticalAudioUri = null
        criticalAudioDisplayName = "Built-in Siren (Emergency Alarm)"
    }

    private fun takePersistablePermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some file providers do not support persistable permissions
        }
    }

    fun queryDisplayName(uri: Uri): String? {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment
    }
}
