# Phase 0 Spike Plan

Goal: prove the Camera2 -> SurfaceTexture -> GL -> SurfaceView pipeline meets latency/stability/battery targets, and measure FOV prediction error, before committing to Phase 1.

## Blockers (as of start)

- RESOLVED: toolchain installed under `D:\Android` (SDK platforms 36 and 37.0, build-tools 36.0.0, platform-tools, Gradle 9.9.2, Temurin JDK 21). Use `. D:\viewfinder\env.ps1` per session. The machine's own `JAVA_HOME` is stale (points to a missing folder). Smoke build (AGP 9.5.2, minSdk 29, targetSdk 36) passes.
- OPEN: measurements need at least one real Android phone (USB debugging). Emulator cannot answer latency/thermal/FOV questions.

## Spike app (throwaway, lives under `spike/`, not reused as-is)

Status: written and compiles (AGP 9.5.2, lint clean of errors). Never run on a device yet. Build/run/collect instructions: `spike/README.md`.

Single activity, no Compose needed. Features:

1. `CameraCapabilityProbe`: dump for every camera ID: facing, hardware level, physical IDs, focal lengths, physical sensor size, pixel array, active array, zoom ratio range, intrinsic calibration (if any), stream sizes for `SurfaceTexture`, FPS ranges, stabilisation modes. Output: JSON to app files dir + logcat. This fills `DEVICE_MATRIX.md`.
2. GL pipeline: custom EGL thread, OES texture, passthrough shader with crop/desqueeze matrix, `SurfaceView` output.
3. Toggles: 30/60 fps, stream size, zoom ratio slider, stabilisation off, desqueeze 1.0-2.0.
4. Metrics overlay: render fps, frame interval jitter, dropped-frame count, `SENSOR_TIMESTAMP` to present delta.
5. Lifecycle stress button: open/close camera N times.

## Experiments and exit criteria

| # | Experiment | Pass criterion |
|---|---|---|
| E1 | Probe all matrix devices | JSON captured, physical-ID availability recorded |
| E2 | Pipeline fps at 30 and 60, 1080p and 1440p | no sustained drops over 10 min |
| E3 | Latency (timestamp delta) | < 2 frames at 30 fps (about 66 ms) on a recent device |
| E4 | 60 min soak with GL + overlay | no crash, thermal status and battery drop recorded |
| E5 | 200x lifecycle cycles (background/foreground, rotate) | no leak/crash, camera reopens |
| E6 | FOV calibration per `CALIBRATION_PROTOCOL.md` | predicted vs measured HFOV within about 2% on at least 2 of 3 devices, else widen the claim |
| E7 | Zoom-ratio vs. GL crop quality | document visible softness at 1.5x, 2x, 3x |

## Deliverable

`docs/phase0/SPIKE_RESULTS.md` with the tables above filled, a go/no-go on custom EGL vs `GLSurfaceView`, and the final minSdk/targetSdk confirmation.
