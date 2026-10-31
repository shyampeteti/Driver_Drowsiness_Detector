# Driver Drowsiness Detection (Android prototype)

A Kotlin Android prototype that uses the front-facing camera, CameraX and the pretrained MediaPipe Face Landmarker to estimate eye openness locally. A small, deterministic Kotlin engine classifies eye closure over time and requests an audible warning for sustained closure. No custom drowsiness model is trained.

> **Environment note:** The two supplied requirements documents were reviewed. The design-flow document proposes GRU/LSTM training, but this prototype intentionally follows the simpler architecture explicitly requested here: pretrained face landmarks plus rule-based EAR/temporal detection. This environment has no Android SDK and cannot reach the model host, so the real model asset is intentionally not replaced with a fake file. Add the official model asset before running the app; full build/runtime verification remains pending until dependencies and Android build tools are available.

## Architecture

- `MainActivity.kt` — minimal UI, permission flow, lifecycle, CameraX binding, and pipeline orchestration.
- `camera/YuvBitmapConverter.kt` — converts CameraX YUV frames to a bitmap for the MediaPipe task. This is a simple compatibility-first conversion and may need optimization on slower devices.
- `vision/FaceLandmarkerHelper.kt` — initializes MediaPipe Face Landmarker in video mode and extracts the two eyes' EAR.
- `detection/EyeAspectRatioCalculator.kt` — pure Kotlin EAR calculation.
- `detection/DrowsinessDetector.kt` — timestamp-based closure tracking, hysteresis, rolling closure ratio, cooldown, and explicit states.
- `audio/AlertManager.kt` — single Android `ToneGenerator`; no overlapping player instances.

## Technology stack

- Kotlin, AndroidX Activity/AppCompat, Material Components
- CameraX Preview and ImageAnalysis (`STRATEGY_KEEP_ONLY_LATEST`)
- MediaPipe Tasks Vision Face Landmarker
- JUnit 4 for pure JVM unit tests

## Pretrained model

The app expects `app/src/main/assets/face_landmarker.task`. Obtain the official Face Landmarker task bundle from:

https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task

The model is used only for face landmarks. Drowsiness detection is **not** a pretrained classifier: EAR thresholds, closure duration, rolling ratio, and alarm policy are implemented as Kotlin rules. The app performs no network inference and contains no code to upload or save camera frames. The model must be bundled for offline runtime.

## Eye-closure logic

EAR is calculated independently for the eyes using the MediaPipe Face Mesh topology:

- Left eye: `[33, 160, 158, 133, 153, 144]`
- Right eye: `[362, 385, 387, 263, 373, 380]`

The formula is `(distance(p2,p6) + distance(p3,p5)) / (2 * distance(p1,p4))`. When both eyes are valid, their EAR values are averaged; if only one is valid, that eye is used. Invalid or missing face observations do not count as open or closed. A 0.20 closed threshold and 0.24 open threshold are initial values only and require empirical calibration for camera position, lighting, glasses, and the individual driver.

The detector uses monotonic elapsed-realtime timestamps. A closure lasting 1.5 seconds initially triggers drowsiness; observations separated by a gap over 500 ms break the continuous-closure interval. A time-windowed PERCLOS-style metric is the fraction of valid eye-state samples classified closed in the last 30 seconds, and is supplementary. It is not asserted to be identical to every research definition of PERCLOS. Thresholds are constructor parameters in `DrowsinessDetector`.

## Open and build

1. Install Android Studio with Android SDK Platform 35 and a compatible JDK (JDK 17 is the usual Android Gradle Plugin 8.7 toolchain; JDK 21 may also run Gradle 8.9+).
2. Add `face_landmarker.task` at `app/src/main/assets/face_landmarker.task`.
3. Open this directory in Android Studio and allow Gradle sync to download dependencies.
4. Build the debug APK using `./gradlew assembleDebug` if a Gradle wrapper is present, or Android Studio's **Build > Build APK(s)**. This workspace does not include a wrapper because wrapper artifacts could not be downloaded here.
5. The expected output after a successful build is `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

Run `./gradlew test` once a wrapper/Gradle installation and Android SDK are available. Tests cover known EAR coordinates, invalid geometry, brief blinks, prolonged closure, cooldown, gap handling, missing face recovery, rolling-window pruning, PERCLOS-style calculation, hysteresis, stop, and error states. These tests have been authored but were **not executed in the current environment**.

## Manual validation checklist

- [ ] First launch and camera permission request
- [ ] Camera preview starts and remains responsive
- [ ] Face detected and EAR diagnostics update
- [ ] Face temporarily moved out of frame; no drowsiness alarm solely from face loss
- [ ] Normal blinking does not trigger an alarm
- [ ] Prolonged eye closure tested while stationary, never while driving
- [ ] Audible alert is emitted and cooldown prevents rapid repeats
- [ ] Reopening eyes clears the drowsiness state/alarm
- [ ] Stop and restart monitoring repeatedly
- [ ] Low-light and glasses scenarios
- [ ] Rotate device and background/foreground the app
- [ ] Deny camera permission and verify explanatory status
- [ ] After installation, disable network and verify model/inference still work

## Limitations and safety

- No device/emulator test or build was possible in the current environment; source integration must be validated after adding the SDK and model.
- EAR thresholds are heuristic and not calibrated for all faces, cameras, eyewear, lighting, or head poses.
- A front-facing phone camera is not a validated driver-monitoring system. This prototype is not safety-certified and must not be relied on as the sole safeguard against drowsy driving.
- The current YUV-to-JPEG-to-Bitmap conversion favors simplicity over throughput and may need a more efficient converter after profiling.
- System audio settings and device policies may affect warning audibility.
- The supplied need-scoping document emphasizes early warning, low nuisance-alarm rates, local smartphone processing, and robustness limitations. The original design-flow document proposes a trained GRU/LSTM, but the requested prototype deliberately omits that training pipeline and uses the simpler EAR/temporal rules described in this README.
