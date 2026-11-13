# DriveSafe

DriveSafe is an Android driver-monitoring app built with Kotlin, CameraX, and MediaPipe Face Landmarker. Face landmarks and derived measurements are processed on the phone. Driving history stores aggregate session data locally; the app does not save camera frames or face images.

## Features

- **Eye monitoring:** Calculates left and right eye aspect ratios separately, then fuses the results. Closure tracking keeps a brief reopening (under 250 ms) within the active episode and records prolonged episodes in a rolling 45-second history.
- **Duration and score:** Closure categories are normal blink (under 200 ms), monitor (200–500 ms), potential drowsiness (500–1000 ms), serious (1000–2000 ms), and critical sustained (2000 ms or longer). The 0–100 score combines sustained closure (40%), PERCLOS (25%), prolonged-closure frequency (20%), and average episode duration (15%), with event-based yawn and nod contributions. A closure of 1000 ms or longer forces at least a drowsy state; 2000 ms or longer forces critical.
- **Recovery and PERCLOS:** Downgrading an alert requires 1.5 seconds of confirmed open-eye observations. PERCLOS uses valid observed eye time in a 30-second window and remains unavailable until at least five seconds of valid intervals have accumulated. Missing or uncertain observations are excluded.
- **Face visibility:** Face lock requires three visible observations spanning at least 500 ms. After lock, 1.5 seconds of face loss activates a separate critical face-visibility alert; recovery requires three visible observations spanning at least 500 ms. Gaps longer than 600 ms restart confirmation. Face loss is tracked separately and is not counted as eye closure.
- **Yawns and nods:** Sustained mouth opening and downward head-pitch events contribute to event counts and score. One shared head-pose estimator supplies pitch and nod events.
- **Custom alert sounds:** In **Audio Settings**, choose separate local audio files for warning and critical alerts, preview them, stop playback, or reset either selection to the built-in tone. Selected alert audio loops for as long as its alert remains active, then stops when the condition clears. The built-in fallback tones are used if a selected file cannot be played. Critical alerts can also vibrate.
- **Silent mode and Do Not Disturb:** Alerts use Android's alarm audio channel, so they can sound when the phone's ringer is set to silent, subject to the phone's alarm volume and manufacturer settings. For alerts to sound during Do Not Disturb, make sure Alarms are allowed in the phone's DND settings. DND configurations that block alarms, a muted alarm volume, or device-specific restrictions can still silence audio. Warning requests have a four-second cooldown; eligible critical conditions can be requested again after five seconds.
- **Session history:** Stores session times, duration, warning and critical playback counts, closure statistics, yawns, nods, separate critical drowsiness and face-loss incident counts, time in monitoring states, termination reason, and time-weighted average PERCLOS. Database operations run on a background executor. Existing database records are retained during the version 1 to version 2 migration.
- **Rest recommendation:** History recommends rest after three critical drowsiness incidents in the previous seven days or at least two in one session. Older records without incident counts are not included in these thresholds.
- **Camera processing:** Camera analysis keeps the latest frame and throttles inference to approximately 10 frames per second. YUV frames are converted directly to RGB without an intermediate JPEG encoding step.

## Project structure

```text
app/src/main/java/com/example/drowsiness/
├── MainActivity.kt                  Dashboard, camera lifecycle, session finalization
├── AudioSettingsActivity.kt         Local alert audio selection and preview
├── SessionHistoryActivity.kt        Session history and rest recommendation
├── SessionAdapter.kt                Session telemetry rows
├── audio/AlertManager.kt             Audio, tone fallback, and vibration
├── camera/YuvBitmapConverter.kt      YUV_420_888 to RGB conversion
├── data/DrivingSession.kt            Persisted session model
├── data/SessionRepository.kt         SQLite helper and asynchronous repository
├── data/RestRecommendation.kt        History threshold rules
├── detection/DrowsinessDetector.kt   Detection state and alert coordination
├── detection/FaceVisibilityTracker.kt Face lock, loss grace, and recovery
├── detection/PerclosCalculator.kt    Rolling valid-interval PERCLOS
├── detection/HeadPoseEstimator.kt   Pitch and nod event estimation
└── vision/FaceLandmarkerHelper.kt   Landmark inference and facial measurements
```

The MediaPipe model file is `app/src/main/assets/face_landmarker.task`. Local session storage uses the SQLite database `driver_safety_sessions.db`.

## Requirements

- Android Studio with Android SDK Platform 35
- JDK 17 or 21 (Android Studio's bundled JDK is suitable)
- An Android phone with a front-facing camera; camera permission is requested when needed

## Build the APK

From the project root in PowerShell:

```powershell
java -version
.\gradlew.bat assembleDebug
```

The installable debug APK is created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Install on an Android phone

1. Build the debug APK using the command above.
2. On the phone, open **Settings → About phone** and tap **Build number** seven times. Enter the device PIN if prompted. Some manufacturers put Build number under **Software information**.
3. Go back to Settings, open **Developer options**, and enable **USB debugging**.
4. Connect the phone to the computer with a data-capable USB cable. Unlock the phone and accept the **Allow USB debugging** prompt, optionally selecting **Always allow from this computer** for your own computer.
5. Copy `app\build\outputs\apk\debug\app-debug.apk` to the phone (for example, to Downloads). Open the APK from the phone's Files app and approve **Install unknown apps** for that file manager if Android asks. Tap **Install**, then open DriveSafe and grant camera permission.

Alternatively, with Android Platform Tools installed, keep the phone connected with USB debugging authorized and run:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

When finished, turn USB debugging off if you do not need it. Menu names can vary slightly between Android manufacturers. The debug APK is intended for direct installation and development; it is not a Play Store release.

## Validation on a phone

Check the camera preview and face indicator in the intended dashboard position. Complete calibration with the face centered, then check normal blinking, prolonged closures, face loss and reacquisition, yawns, nods, warning cooldown, critical audio and vibration, and selected custom sounds. Stop monitoring and inspect the saved history, including unavailable PERCLOS when there is not enough valid data and the separate face-loss and drowsiness incident counts. Repeat camera checks in daylight and low cabin light, and confirm responsive preview without sustained heating.

Build success alone does not verify camera framing, lighting, sound, vibration, or driver positioning on a particular phone.
