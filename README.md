# DriveSafe AI

DriveSafe AI is an Android driver-monitoring prototype built with Kotlin, CameraX, and MediaPipe Face Landmarker. Face landmarks and derived metrics are processed on-device. The app stores aggregate session telemetry locally; it does not store camera frames or face images.

## Detection behavior

- Left and right eye aspect ratios (EAR) are classified independently and combined with temporal closure tracking. Eye closures, rolling PERCLOS, yawning, and head pose contribute to the drowsiness score.
- Rolling PERCLOS is the fraction of valid observed eye time classified closed in a 30-second window. It requires five seconds of valid intervals. Gaps and unknown eye measurements are excluded rather than treated as open or closed.
- Face visibility is tracked separately from eye measurements. Face-loss monitoring starts only after three visible observations spanning at least 500 ms. A 1.5-second loss activates a separate critical condition; clearing it requires three consecutive visible observations spanning at least 500 ms. Sequences with gaps over 600 ms restart lock or recovery confirmation.
- The dedicated face-loss warning is: **CRITICAL — DRIVER FACE NOT VISIBLE. CHECK DRIVER AND CAMERA.** Face loss does not establish that the driver is asleep.
- Drowsiness-critical incidents and face-loss incidents have separate counters. Existing warning and critical playback-event totals remain available for compatibility, while incident counts increment only on activation. Repeated audio playback does not create incidents. Critical playback repeats after 5 seconds while an eligible critical condition remains active; warning playback uses a 4-second cooldown.
- A single `HeadPoseEstimator`, owned by `FaceLandmarkerHelper`, produces pitch and nod events. Confirmed nod events are passed into the drowsiness scoring engine.

## Alerts

`AlertManager` plays user-selected warning or critical audio with built-in tone fallback. Critical playback can also use the vibration setting. Active conditions remain represented between playback requests; monitoring shutdown and resolved conditions explicitly stop the alert manager.

## Sessions and history

Sessions include warning and critical playback-event counts, closure duration, yawns, nods, separate drowsiness-critical and face-loss incident counts, time in monitoring states, termination reason, and a time-weighted average of rolling PERCLOS values. The session average uses valid consecutive PERCLOS observations only. History displays “—” when a time-weighted average or a newer incident metric is unavailable. Older records are preserved; their missing new fields remain unknown.

SQLite lives in `data/SessionRepository.kt` (`SessionDbHelper`). Schema version 2 migrates the version 1 table while preserving existing row IDs and stored telemetry. The repository performs database work on one background executor, closes cursors, and posts results to the main thread.

History shows a rest recommendation when saved records show either three critical drowsiness incidents in the rolling previous seven days, or at least two in one session. Older records without activation-based incident counts and face-loss incidents do not contribute.

## Project layout

```text
app/src/main/java/com/example/drowsiness/
├── MainActivity.kt                  Dashboard, CameraX lifecycle, session finalization
├── SessionHistoryActivity.kt        Session history and rest recommendation
├── SessionAdapter.kt                Session telemetry rows
├── AudioSettingsActivity.kt         Custom alert audio configuration
├── audio/AlertManager.kt             Audio, tone fallback, and vibration
├── camera/YuvBitmapConverter.kt      Direct YUV_420_888 to RGB conversion
├── data/DrivingSession.kt            Persisted session model
├── data/SessionRepository.kt         SQLite helper, schema migration, async repository
├── data/RestRecommendation.kt        History threshold rules
├── detection/DrowsinessDetector.kt   Detection state and alert coordination
├── detection/FaceVisibilityTracker.kt Face lock, loss grace, and recovery
├── detection/PerclosCalculator.kt    Rolling valid-interval PERCLOS
├── detection/HeadPoseEstimator.kt   Pitch and nod event estimation
└── vision/FaceLandmarkerHelper.kt   MediaPipe inference and facial metrics
```

The MediaPipe model asset is `app/src/main/assets/face_landmarker.task`. Camera analysis keeps only the latest frame and throttles inference to approximately 10 FPS. YUV conversion avoids the previous NV21-to-JPEG-to-Bitmap round trip; color and speed should still be checked on the target phone.

## Build and test

Use Android Studio's bundled JDK 21 or another supported JDK. From the project root:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Physical-device validation

1. Install the debug APK on the intended phone and grant camera permission.
2. Verify the preview orientation, crop, color, and face indicator in the normal dashboard position and at screen rotation changes.
3. Start monitoring and complete calibration with the face centered; confirm normal blinks do not trigger alerts.
4. Cover the camera or turn away after the face is locked. Confirm the dedicated red face-loss warning appears after the grace period, audio and configured vibration work, and a single return frame does not clear it.
5. Restore a stable face view and confirm the face-loss warning clears only after stable reacquisition. Separately verify long eye closures, yawns, nods, custom sounds, and cooldown behavior.
6. Stop monitoring and check the saved session telemetry, PERCLOS “—” behavior when no valid average exists, incident separation, and the seven-day recommendation thresholds in History.
7. Repeat the camera checks under daylight and low cabin light, and confirm the preview remains responsive without sustained heating or memory growth.

Unit tests and APK builds do not replace on-device camera, color, audio, vibration, and driver-position validation.
