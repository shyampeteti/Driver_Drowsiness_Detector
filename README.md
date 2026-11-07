# DriveSafe AI — Intelligent Driver Safety System

An advanced, on-device Android intelligent driver monitoring and safety system built with **Kotlin**, **CameraX**, and **MediaPipe Face Landmarker**.

All facial landmark detection, EAR/MAR calculation, duration-based closure tracking, multi-factor drowsiness scoring, and alert management operate strictly on-device with zero cloud dependencies or image data transmission.

---

## Key Features & Upgrades

### 1. Intelligent Duration-Weighted Drowsiness Detection
- **Duration-Based Episode Classification:**
  - `< 200 ms`: Normal blink
  - `200 – 500 ms`: Monitor
  - `500 – 1000 ms`: Potential drowsiness
  - `1000 – 2000 ms`: Serious warning (Level 2)
  - `> 2000 ms`: Critical sustained closure (Level 3)
- **Independent Eye Tracking:** Monitors left and right eye EAR independently, detecting asymmetric closure or landmark occlusion.
- **Anti-Flicker Hysteresis:** Micro-reopenings (< 250 ms) do not prematurely wipe out an active closure event or reset drowsiness risk.
- **Continuous Closure Priority:** Continuous closure for several seconds receives substantial priority over high-frequency blinks.
- **Rolling Episode History:** Tracks the count and average duration of prolonged closures in a 45-second rolling window.

### 2. Adaptive Drowsiness Scoring Engine
- Computes a real-time composite drowsiness score (0.0 – 100.0) combining:
  1. Continuous eye-closure duration (40%)
  2. Rolling PERCLOS over 30s window (25%)
  3. Frequency of prolonged eye closures (20%)
  4. Average duration of closure episodes (15%)
  - Plus modular contributions from sustained yawning and downward head nodding.
- **Stable State Transitions:**
  - `NORMAL / MONITORING` (Score < 40 and closure < 1000 ms)
  - `DROWSY` (Score 40–70, or closure 1000–2000 ms, or repeated prolonged closures)
  - `CRITICAL` (Score >= 70, or sustained closure >= 2000 ms)
  - Recovery requires confirmed open eyes for a sustained period (1500 ms) before downgrading active alerts.

### 3. Custom Local Audio Selection
- **Audio Settings Screen (`AudioSettingsActivity`):**
  - Select custom audio files (MP3, WAV, AAC, etc.) from device storage using the system file picker (`OpenDocument`).
  - Persists storage URI permissions across device reboots without demanding broad file permissions.
  - Independent customization of:
    - **Level 2: Warning Sound**
    - **Level 3: Critical Alert Sound**
  - Preview / Play, Stop, and Default Reset controls.
  - Automatic fallback to built-in emergency alert tones (`ToneGenerator`) if a custom file is missing or unreadable.

### 4. Escalating Alert System & Tactile Feedback
- **Level 1 (Normal):** Green status, active monitoring, audio silent.
- **Level 2 (Drowsiness Warning):** Amber alert banner, warning audio playback, cooldown interval (4s) preventing alert spam.
- **Level 3 (Critical Alert):** Red pulsing alert banner, critical audio playback, immediate escalation bypassing warning cooldown, synchronized pulsed tactile vibration pattern.

### 5. Driving Session Analytics & Local Persistence
- Records comprehensive driving trip telemetry:
  - Session start and end timestamps
  - Total trip duration
  - Number of warning events & critical events
  - Longest continuous eye-closure duration (ms)
  - Average PERCLOS (%)
  - Time spent in each state (Normal, Drowsy, Critical, Face Lost)
  - Total yawning events
- **Local SQLite Persistence:** `SessionRepository` stores session history in `driver_safety_sessions.db` on a background thread.
- **Session History Screen (`SessionHistoryActivity`):** View past trips, safety grades, and summary badges.
- **Privacy Assurance:** Only aggregate numerical telemetry is stored. No camera frames, landmarks, or face images are ever stored or uploaded.

### 6. Modular Vision Improvements
- **Yawning Detection (`MouthAspectRatioCalculator`):** Calculates 8-point MAR and classifies sustained mouth opening (> 1500 ms) as yawning.
- **Head Pose & Nodding Estimation (`HeadPoseEstimator`):** Estimates vertical pitch from forehead, nose, and chin distances to detect downward nodding.
- **Personalized Baseline Calibration (`CalibrationManager`):** Adapts driver's baseline open EAR over a 3.5-second initialization window, safely bounded within `[0.16, 0.25]` for closure threshold to prevent learning drowsy eyes as normal.

### 7. Modern Automotive Dashboard HUD
- Automotive dark-mode cockpit interface (`#0B0F19` / `#161F30`).
- Compact, high-performance camera preview with real-time "Face Locked" status indicator.
- Dynamic color-coded alert banner.
- Real-time telemetry cards for EAR, Closure Duration, PERCLOS, Drowsiness Score, and Event Counters.
- Quick action controls: Start/Stop, Audio Settings, History, and Calibration.

---

## Architecture Overview

```
com.example.drowsiness
├── MainActivity.kt               // Automotive dashboard, CameraX binding, UI orchestrator
├── AudioSettingsActivity.kt      // Custom audio file picker and preview controls
├── SessionHistoryActivity.kt     // Driving session analytics viewer
├── SessionAdapter.kt             // RecyclerView adapter for past driving sessions
│
├── audio
│   ├── AlertManager.kt           // Multi-level player (MediaPlayer + ToneGenerator + Vibrator)
│   └── AudioSettingsManager.kt   // Storage Access Framework URI & preference persistence
│
├── camera
│   └── YuvBitmapConverter.kt     // YUV_420_888 to Bitmap converter for MediaPipe
│
├── data
│   ├── DrivingSession.kt         // Session telemetry data model
│   ├── SessionDbHelper.kt        // SQLite local database
│   └── SessionRepository.kt      // Async repository for session analytics
│
├── detection
│   ├── DetectionState.kt         // State enums, AlertLevel, DurationCategory, Snapshot
│   ├── DrowsinessDetector.kt     // Detection pipeline coordinator
│   ├── DrowsinessScoringEngine.kt// Multi-factor weighted scoring & hysteresis engine
│   ├── ClosureEpisodeTracker.kt  // Duration-based closure episodes & anti-flicker tracking
│   ├── PerclosCalculator.kt      // Rolling PERCLOS window calculation
│   ├── EyeAspectRatioCalculator.kt// 6-point EAR calculation & landmark indices
│   ├── MouthAspectRatioCalculator.kt// 8-point MAR & sustained yawn detector
│   ├── HeadPoseEstimator.kt      // Vertical head pitch & nodding detector
│   └── CalibrationManager.kt     // Bounded baseline EAR calibration
│
└── vision
    └── FaceLandmarkerHelper.kt   // MediaPipe Face Landmarker video pipeline & extraction
```

---

## Build & Run

### Prerequisites
- Android Studio Ladybug / Meerkat / 2024.2+ with Android SDK Platform 35.
- Java JDK 17 or JDK 21 (Android Studio bundled JBR works out of the box).
- Pretrained model asset located at `app/src/main/assets/face_landmarker.task`.

### Build Commands
Run from the project root:

```powershell
# Run all unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Verification & Testing Guide

| Test Scenario | Procedure | Expected Result |
|---|---|---|
| **Normal Blinking** | Blink normally (< 200 ms) | Categorized as Normal Blink; score remains < 15; status stays green `MONITORING`. |
| **Duration Escalation** | Close eyes for 600 ms | Status moves to Potential Drowsiness; score increases. |
| **Drowsy Warning** | Close eyes for 1200 ms | Escalates to `DROWSY`; Level 2 Warning audio plays; amber warning banner displays. |
| **Critical Sustained Closure** | Close eyes for > 2000 ms | Escalates immediately to `CRITICAL`; Level 3 audio + vibration trigger; red banner displays. |
| **Anti-Flicker Hysteresis** | Close eyes for 1000 ms, flutter open for 100 ms, close again | Episode is not prematurely cleared; risk remains elevated. |
| **Face Loss Handling** | Cover camera or turn head away | Displays `FACE_NOT_DETECTED`; amber searching indicator; does not count missing face as closed eyes or safe. |
| **Face Return** | Re-align face with camera | Recovers smoothly to `MONITORING` once landmarks are verified. |
| **Custom Audio Selection** | Open Audio Settings, select an MP3/WAV file, preview it, save | Selected audio plays during warning/critical events; persists after app restart. |
| **Session Analytics** | Monitor for > 15s, stop monitoring, tap History | New trip appears in history with duration, event counts, max closure, and safety status. |
