# DriveSafe — Driver Drowsiness Monitoring

DriveSafe is a native Android application written in Kotlin. It uses the front camera and MediaPipe Face Landmarker to measure facial cues associated with drowsiness. Camera frames and derived measurements are processed locally. Session history stores numerical trip summaries in SQLite; the app does not save camera frames or face images.

> DriveSafe is a driver-awareness aid, not a medical diagnostic tool or a guarantee that it is safe to continue driving. Drivers should pull over safely and rest if they feel sleepy.

## How the application works

```text
CameraX frame (YUV_420_888)
        ↓
YUV-to-RGB Bitmap conversion and rotation
        ↓
MediaPipe Face Landmarker (one face, VIDEO mode)
        ↓
Left/right EAR · mouth MAR · estimated head pitch
        ↓
Calibration, eye classification, closure/PERCLOS/event tracking
        ↓
Drowsiness score and state decision
        ↓
Dashboard · audio/vibration alerts · SQLite session summary
```

`MainActivity` owns the camera and screen lifecycle. `FaceLandmarkerHelper` turns a bitmap into facial measurements. `DrowsinessDetector` coordinates the detection components and produces a `DetectionSnapshot`. The Activity renders that snapshot, sends eligible alert requests to `AlertManager`, and records session telemetry through `SessionRepository`.

## Detection methods

### Eye Aspect Ratio (EAR)

For each eye, six facial landmarks are converted from normalized image coordinates to pixels. EAR compares two vertical eyelid distances with the horizontal eye width:

```text
EAR = (distance(p2,p6) + distance(p3,p5)) / (2 × distance(p1,p4))
```

A smaller EAR generally means the eye is more closed. Left and right eyes are classified independently. The classifier smooths each raw EAR using an exponential moving average (`alpha = 0.50` when a previous value exists), then uses separate personalized closed/open thresholds. Between those thresholds it retains the prior confident state. One closed eye with the other open is treated as asymmetric and fused as open; one closed plus one uncertain is uncertain. This avoids treating a wink or unreliable single eye as confirmed bilateral closure.

### Calibration and baseline adaptation

Calibration lasts 3.5 seconds by default and gathers at least 20 valid samples per eye. Samples are accepted when pitch is within 15 degrees (or unavailable). The estimator trims the lowest 20% and highest 10% of sorted samples, then checks the baseline range and sample variability. Successful calibration sets per-eye thresholds at 65% (closed) and 80% (open) of baseline, with at least a 0.02 gap. If calibration fails, defaults are used: closed EAR 0.18 and open EAR 0.22.

`AdaptiveBaselineTracker` can adjust a calibrated baseline slowly (rate 0.002), bounded to ±15% of the original baseline. It updates only when both eyes are open, closure duration is zero, score is below 25 in MONITORING, pitch is stable, no yawn or nod is active, and both observations are within 20% of the current baseline.

### Closure episodes

`ClosureEpisodeTracker` measures closure duration from monotonic timestamps. An open-eye interval must remain confirmed for 250 ms before the closure episode is concluded; this is the anti-flicker rule. Completed episodes are kept for 45 seconds. Episodes of at least 500 ms count as prolonged. The app also tracks the longest closure in a session.

Duration categories are defined in `DetectionState.kt`; the scoring engine’s explicit duration boundaries are under 200, 200, 500, 1,000 and 2,000 ms. A closure of at least 1,000 ms can force the drowsy state; at least 2,000 ms can force critical.

### PERCLOS

PERCLOS is the proportion of valid observed eye time classified as closed:

```text
PERCLOS = closed valid time / total valid observed time
```

`PerclosCalculator` uses intervals between consecutive valid samples in a rolling 30-second window, rather than counting frames. The earlier sample’s eye state is assigned to the interval. Intervals over 500 ms and intervals across a declared continuity break are excluded as unknown time. PERCLOS is unavailable until at least five seconds of valid interval time have accumulated. Missing faces and uncertain eye observations are not evidence that eyes are open or closed.

### Yawn detection (MAR)

The mouth aspect ratio uses mouth-corner landmarks 61 and 291 and three upper/lower lip pairs (13/14, 82/87, 312/317):

```text
MAR = (center lip gap + left lip gap + right lip gap) / (2 × mouth width)
```

`YawnTracker` starts timing when MAR is at least 0.65. It confirms a sustained yawn after 1,500 ms. MAR below 0.50 resets the candidate; values between 0.50 and 0.65 keep the candidate window open but do not advance the timer. When mouth metrics are available, the center vertical gap must be at least 15% of mouth width. A 6-second cooldown limits repeated counts. This is a geometric/time heuristic and cannot perfectly distinguish every yawn from speech or other mouth movements.

### Head pitch and nods

`HeadPoseEstimator` uses forehead (10), nose tip (1), chin (152), and eye landmarks (33, 263). It computes the ratio between forehead-to-nose and nose-to-chin distances, then estimates pitch as `(1 − ratio) × 45°`. A pitch below −18° is considered head-down. It must persist for 1,200 ms to register a nod event. This is a 2D approximation, not a full 3D pose solution.

## Drowsiness score and state logic

The scoring engine calculates four component scores, each on a 0–100 scale:

- **Closure component:** under 200 ms → 0; 200–499 ms → `5 + (duration − 200) × 0.10`; 500–999 ms → `35 + (duration − 500) × 0.070`; 1,000–1,999 ms → `70 + (duration − 1,000) × 0.030`; at least 2,000 ms → 100.
- **PERCLOS component:** `clamp(0, 100, (PERCLOS fraction / 0.40) × 100)`. Thus 0.40 (40%) maps to 100.
- **Prolonged-frequency component:** 0 prolonged episodes → 0; 1 → 30; 2 → 65; 3 or more → 100. The detector supplies the count from its recent episode history.
- **Average-episode component:** `clamp(0, 100, ((average duration ms − 150) / 450) × 100)`. Durations at or below 150 ms map to 0; at or above 600 ms map to 100.

The exact combined formula implemented in `DrowsinessScoringEngine.kt` is:

```text
Final score = clamp(0, 100,
    0.40 × closure score
  + 0.25 × PERCLOS score
  + 0.20 × prolonged-closure frequency score
  + 0.15 × average episode score
  + current yawn bonus
  + current nod bonus)
```

A newly confirmed yawn adds 12 points, with the pending yawn bonus capped at 25. A newly confirmed nod adds 15, capped at 30. The pending bonuses are multiplied by 0.85 on each scoring evaluation, so they decay over time. A sustained closure then applies a score floor: at least 52 for closure ≥1,000 ms, or at least 80 for closure ≥2,000 ms. The final value is clamped to 0–100.

State candidates are selected as follows:

| State | Condition |
|---|---|
| `MONITORING` | Score < 50 and closure < 1,000 ms |
| `DROWSY` | Score ≥ 50 or closure ≥ 1,000 ms |
| `CRITICAL` | Score ≥ 75 or closure ≥ 2,000 ms |

Escalation is immediate. Recovery to a lower state requires 1,500 ms of confirmed open-eye observations; renewed closure resets the recovery timer. Face loss is a separate visibility condition and is not added to the drowsiness score.

## Face visibility

`FaceVisibilityTracker` confirms a stable face before reporting a face lock. Project defaults require three visible observations spanning at least 500 ms. Following a confirmed lock, 1.5 seconds of face loss can raise a separate critical face-visibility alert. Reacquisition also requires three observations spanning at least 500 ms; gaps longer than 600 ms restart confirmation. Face loss is not counted as eye closure or as safe/open-eye time.

## Android camera, threading and lifecycle

The manifest requests `CAMERA` at runtime and declares `VIBRATE`. There is no foreground camera service; CameraX use cases are bound to `MainActivity`’s lifecycle.

- CameraX provides a `Preview` and an `ImageAnalysis` use case using the front camera.
- `STRATEGY_KEEP_ONLY_LATEST` prevents stale frames from accumulating.
- `MainActivity` throttles inference to roughly one frame per 100 ms (about 10 FPS) and uses an atomic guard to avoid overlapping frame processing.
- `YuvBitmapConverter` converts YUV_420_888 directly to RGB bitmap data, avoiding a JPEG encode/decode step.
- The bitmap is rotated according to CameraX image metadata, passed to MediaPipe, then recycled.
- The analyzer closes every `ImageProxy` in a `finally` block so CameraX can reuse buffers.
- Detection runs on a single-thread executor; UI updates are posted to the main thread.

## Alerts and audio configuration

`AudioSettingsActivity` uses Android’s `OpenDocument` picker for `audio/*`, saves URI access for later use, and supports separate warning and critical sounds, preview/stop, default reset, and a critical-vibration switch. `AlertManager` plays the selected sound when available and provides built-in tone fallback. Alert requests use cooldowns to limit repeated requests; warning and critical paths have separate behavior in the implementation. Audio is configured for the alarm usage channel. Whether it sounds in silent or Do Not Disturb mode still depends on alarm volume, the phone’s DND alarm setting, and manufacturer restrictions. Critical vibration requires vibration to be enabled in settings and permitted by the device.

## Session analytics and local data

`DrivingSession` is the session data model. `SessionRepository` provides asynchronous database access to the local `driver_safety_sessions.db`. Session records include time and duration, warning/critical plays, closure statistics, PERCLOS, yawns/nods, time in monitoring/drowsy/critical/face-lost states, incident counts, and session end reason. `SessionFinalizationGuard` helps ensure a session is only finalized once. `RestRecommendation` derives its message from prior critical incident counts: three critical drowsiness incidents in the preceding seven days or at least two in one session. Older records without the required counts are excluded.

No camera frames, face images or raw landmarks are listed as session fields. Database calls are kept off the UI thread.

## Project structure

```text
app/src/main/
├── AndroidManifest.xml
├── assets/face_landmarker.task
├── java/com/example/drowsiness/
│   ├── MainActivity.kt
│   ├── AudioSettingsActivity.kt
│   ├── SessionHistoryActivity.kt
│   ├── SessionAdapter.kt
│   ├── audio/
│   │   ├── AlertManager.kt
│   │   └── AudioSettingsManager.kt
│   ├── camera/YuvBitmapConverter.kt
│   ├── data/
│   │   ├── DrivingSession.kt
│   │   ├── RestRecommendation.kt
│   │   ├── SessionFinalizationGuard.kt
│   │   └── SessionRepository.kt
│   ├── detection/
│   │   ├── AdaptiveBaselineTracker.kt
│   │   ├── CalibrationManager.kt
│   │   ├── ClosureEpisodeTracker.kt
│   │   ├── DetectionState.kt
│   │   ├── DrowsinessDetector.kt
│   │   ├── DrowsinessScoringEngine.kt
│   │   ├── EyeAspectRatioCalculator.kt
│   │   ├── EyeClassifier.kt
│   │   ├── FaceVisibilityTracker.kt
│   │   ├── HeadPoseEstimator.kt
│   │   ├── MouthAspectRatioCalculator.kt
│   │   └── PerclosCalculator.kt
│   └── vision/FaceLandmarkerHelper.kt
└── res/
    ├── drawable*/                 App icon and UI backgrounds
    ├── layout/                    Dashboard, settings, history and row layouts
    └── values/                    Colors, strings and styles
```

The source test tree includes unit tests for eye metrics/classification, calibration, episode timing, PERCLOS, face visibility, scoring, yawns, detector behavior, session finalization and rest recommendations. Android instrumentation tests cover database migration.

## Technologies and dependencies

Configured in `app/build.gradle.kts`:

- Kotlin Android plugin 2.0.21 and Android Gradle Plugin 8.7.3.
- Android SDK compile/target 35, minimum SDK 26, Java/Kotlin bytecode target 17.
- AndroidX Core KTX 1.15.0, AppCompat 1.7.0, Material Components 1.12.0, Activity KTX 1.10.0.
- CameraX 1.4.1 modules: core, Camera2, lifecycle and view.
- MediaPipe Tasks Vision 0.10.21.
- JUnit 4.13.2 and AndroidX test runner/core/JUnit extensions for tests.

CameraX handles camera use cases and lifecycle integration; MediaPipe provides face landmarks; AndroidX and Material provide Activities, permissions/results, and UI widgets. Kotlin/JVM code implements measurement, temporal logic, scoring and persistence coordination.

## Build

Requirements: Android Studio with Android SDK Platform 35, and JDK 17 (Android Studio’s bundled JDK is suitable).

From the project root in PowerShell:

```powershell
java -version
.\gradlew.bat assembleDebug
```

The debug APK is created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Install on an Android phone

1. Build the APK using the command above.
2. On the phone, open **Settings → About phone** and tap **Build number** seven times. Enter the PIN if prompted. On some phones, Build number is under **Software information**.
3. Open **Developer options** and enable **USB debugging**.
4. Connect the phone to the computer with a data-capable USB cable, unlock it and approve the USB debugging prompt.
5. Either copy the APK to the phone, open it from Files and allow **Install unknown apps** for that file manager when asked; or install from the computer with Android Platform Tools:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

6. Launch DriveSafe and grant camera permission. Disable USB debugging afterward if it is no longer needed. Settings labels may differ by manufacturer. This debug APK is for development/direct installation, not a Play Store release.

## Phone validation

Check front-camera framing, complete calibration with the face centered, and confirm dashboard readings. Try normal blinking, prolonged closure, face loss and reacquisition, yawns, nods, custom audio, warning cooldown, critical audio/vibration and session history. Check sound behavior with the phone’s alarm volume and DND alarm policy. Lighting, device performance, camera placement and sound behavior vary by phone; a successful build alone does not establish reliable operation in every vehicle.
