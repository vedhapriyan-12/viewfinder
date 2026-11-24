# Phase 0 Spike Results (in progress)

Raw data: `results/<device>/` (probe JSON, metrics CSV, screenshots).

## Device 1: Xiaomi Redmi Note 13 Pro 5G (2312DRA50I, "garnet"), Android 16 / API 36, SM7435, Adreno 710 (ES 3.2)

### E1 Probe: DONE

| Fact | Value | Implication for the product |
|---|---|---|
| Cameras exposed to third-party apps | 2: back `0`, front `1`. No physical sub-camera IDs. | The ultrawide (and any tele) is hidden from apps. Confirms the OEM-restriction risk. |
| Hardware level | LEVEL_3 (both) | Full manual/raw-class feature set available. |
| Back camera optics | f = 6.19 mm, f/1.65, sensor 9.139 x 6.854 mm, 4080 x 3060 active | Native HFOV = 2*atan(9.1392/(2*6.19)) = about 72.9 degrees (full 4:3 array). |
| Intrinsics | fx = fy = 2763.39 px, cx = 2040, cy = 1530 | HFOV from fx = 2*atan(4080/(2*2763.39)) = about 72.9 degrees. Same figure as the metadata route, so this only shows the two routes are self-consistent. Real accuracy needs the rig (E6). |
| Zoom ratio range | 1.0 - 10.0 | No ratio below 1, so nothing wider than the main camera. Widest achievable HFOV is about 72.9 degrees. |
| Max AE FPS | 30 (no 60 fps range) | 60 fps must be capability-gated, not assumed. |
| Timestamp source | REALTIME | Latency measurement is valid. |
| Stabilisation | video OFF/ON, optical OFF/ON | Both can be forced off for FOV work. |
| Rotate-and-crop | NONE only | No HAL rotation surprise on this device. |
| Distortion coefficients | all zeros | Distortion is corrected inside the ISP, or not reported. Cannot model it app-side. |
| Focus | calibration APPROXIMATE, min focus 10 D (10 cm) | `LENS_FOCUS_DISTANCE` is only a rough estimate. |
| 16:9-class SurfaceTexture sizes | 3840x2160, 2304x1296, 1920x1080, 1280x720; also 4080x1832 (2.23:1) | Plenty of preview choices. |

Design consequences:
1. FOV matching must report the achievable range per device. Targets wider than about 72.9 degrees HFOV (for example 14 mm on an approximately 25 mm wide sensor) cannot be matched here; the UI must say so and show the widest possible view.
2. Narrower targets rely on zoom ratio up to 10x, so effective resolution drops as lenses get longer (E7 still to measure).
3. The `fps` toggle in the spike silently falls back to 30 here. The product needs to hide 60 fps when unsupported.

### E2 Pipeline fps: PARTIAL PASS

30 fps target at 1920x1080 and 1920x1440: measured 29.8-29.9 fps, worst frame gap 33.3 ms. About 33 late frames total, all at startup and stream changes; none added during steady state. 60 fps not testable on this device. 1440p and 10 minute run still to do.

### E3 Latency: PROVISIONAL FAIL against the 2-frame target

Average 91-93 ms, max 97-99 ms (sensor timestamp to after the GL swap), roughly 2.8 frames at 30 fps.
Caveat: the sensor timestamp is the start of exposure, so this figure includes exposure and readout time and is an upper bound on pipeline delay. It also excludes display scan-out.
Recommendation: restate the target as "at most 3 frames (about 100 ms) sensor-to-queued at 30 fps" and add a glass-to-glass check (phone filming a running timer or LED) before declaring pass/fail.

### E4 Soak: SKIPPED BY DECISION (partial 288 s sample only)

Owner chose to skip the 60 minute run to move on to the product UI. Partial data (`results/redmi-note-13-pro-5g/soak_partial_metrics.csv`, about 4.8 minutes, phone on USB power): steady 29.7-30.7 fps, worst gap 33.3 ms, 5 late frames in total (startup only), latency 89-91 ms average, thermal status NONE, battery temperature steady at 37.5 C.
This does NOT show sustained-load behaviour. Revisit during Phase 7 hardening (thermal degradation logic needs real throttling data), ideally on battery, with the PC-side observer `tools/soak-watch.ps1`.

### E5 Lifecycle stress: PASS (200 of 200 cycles, 0 failures, 0 crashes)

Third run (harness fixed, started from the PC, phone untouched): app counter `ok=200 fail=0`. The retained logcat tail (the log buffer only keeps the last 90 cycles) shows open-to-first-result of median 420 ms, p95 432 ms, max 437 ms, with no foreign camera events. Resource leaks are not measured here (no memory trace), so "no crash" is the claim, not "no leak".

Earlier runs, kept for the record:

Second run (fixed harness, started from the PC): 12 ok, 0 fail, then ended early. The log shows a screenshot-share chooser and the WhatsApp camera opening at that moment. Pausing the app resets the stress counter (by design), and Android evicted our camera session with a clean `disconnected` callback, no crash. That is a useful positive for the product: losing the camera to another app is survivable.

### E5 history: FIRST RUN INVALID

First run (about 143 cycles, 68 ok / 75 fail) is not a valid stability measurement:
- The first 9 close/open cycles were clean (about 450-600 ms from open to first result).
- Failures started when a second open chain ran in parallel with the first (two `OPENING` lines 5 ms apart, no `CLOSED` between). Either a button was tapped during the run or one error produced several callbacks that each started a new cycle. The harness had no guard against either. Fixed: one result per attempt, taps ignored while stress runs.
- Real-world finding captured by accident: Android's `CameraService` rejected our open with `CAMERA_IN_USE ... Higher-priority client using camera`, naming `com.whatsapp` as the holder. So another app grabbing the camera mid-session happens in practice. The product camera state machine must treat `CAMERA_IN_USE` and `disconnected` as normal events: retry with backoff, show "camera in use by another app", never crash.
- The soak was started during the stress run, so its first rows are also invalid. Run soak alone, after stress.

### E6 FOV calibration: NOT DONE (needs the physical rig, see `CALIBRATION_PROTOCOL.md`)

### E7 Zoom vs GL crop: NOT DONE

## Orientation: IMPORTANT DEVICE FINDING (resolved for landscape-left)

Symptom: preview looked sideways and squashed with the textbook formula `rel = (sensorOrientation - displayRotation) mod 360`.

Root cause: on this device the `SurfaceTexture` transform matrix is not just a vertical flip. It already contains a 90 degree clockwise rotation (matrix `x=(0,-1) y=(-1,0) t=(1,1)`), baked in by the camera stack. Applying the textbook rotation on top double-rotated the image, and treating the buffer as 1920x1080 stretched a portrait-shaped image into a landscape box.

Verified facts (phone held landscape, top to the left, ROTATION_90, sensorOrientation 90):
- Accelerometer pose, window rotation and `Surface` rotation all agreed (so the phone's reported tilt was fine).
- Textbook rotation for the RAW buffer = 0. The matrix already applied 90 CW. Our extra rotation must therefore be 0 - 90 = 270 CW.
- With that fix the preview is upright, correctly proportioned, no squash (screenshot `results/redmi-note-13-pro-5g/shot_fix1.png`).

Rule for the product (not yet verified on other phones or poses):
1. Derive `texRot` from the transform matrix (where the quad's "up" direction lands), never assume it is 0.
2. `extra = (rel - texRot) mod 360`.
3. Effective image size = buffer size swapped when `texRot` or `extra` is 90/270. Use the effective size for aspect, FOV and crops, never the raw buffer size.
4. Cover this with an on-device test pattern in Phase 3, on every device in the matrix, in all four poses and for the front camera.

Still untested: portrait, reverse landscape, reverse portrait, front camera, other phones. Whether other devices bake rotation into the matrix is unknown, which is exactly why the rule reads the matrix.

Other notes from the diagnostics: Android Auto-rotate is OFF on this phone (`accelerometer_rotation=0`); the app still follows the sensor because it requests `fullSensor`. The matrix and accelerometer readouts stay in the spike overlay for later device testing.

## Bugs found by running on a device

1. `window.insetsController` used before `setContentView` crashed on launch (decor view null). Fixed.
2. (Found by lint before the first run) `SCALER_ROTATE_AND_CROP` is API 31, not 30. Fixed.
