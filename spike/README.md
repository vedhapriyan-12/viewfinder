# Viewfinder spike (throwaway)

Phase 0 test app. Answers experiments E1-E7 in `../docs/phase0/SPIKE_PLAN.md`. Do not grow this into the product.

## Build and install

```powershell
. D:\viewfinder\env.ps1
cd D:\viewfinder\spike
.\gradlew.bat assembleDebug
adb devices                       # phone must be listed (USB debugging on)
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Controls (bottom bar)

| Button | Does | Experiment |
|---|---|---|
| cam | cycle camera IDs (logical + front) | E1, E6 |
| phys | cycle physical sub-cameras of the current logical camera | E1, E6 |
| size | cycle SurfaceTexture preview sizes | E2, E7 |
| fps | 30 / 60 fps target | E2 |
| stab | stabilisation off (video + OIS) / default | E6 |
| desq | anamorphic desqueeze 1.0 / 1.25 / 1.33 / 1.5 / 1.8 / 2.0 (GL) | pipeline check |
| glzoom | GL-side digital crop 1x / 1.5x / 2x / 3x | E7 |
| rot | manual rotation override (if the image is sideways/upside down) | orientation check |
| probe | write `probe_<model>.json` | E1 |
| stress | 200 close/open cycles, counts ok/fail | E5 |
| soak | log one CSV row per 10 s to `metrics.csv` | E4 |
| zoom bar | camera zoom ratio (API 30+) or crop region (API 29) | E6, E7 |

Overlay shows fps, worst frame gap, late frames, sensor-to-queued latency, thermal status, battery, and live capture-result values (focal length, zoom, crop, active physical camera, intrinsics).

## Collecting results

```powershell
adb pull /sdcard/Android/data/app.viewfinder.spike/files/ .\results\<device-name>\
adb logcat -s VF_MAIN VF_CAMERA VF_RENDER VF_PROBE
```

## Known limits of this spike

- Latency = sensor timestamp to after `eglSwapBuffers`. It excludes display scan-out. Only shown when the camera timestamp source is REALTIME (otherwise `n/a`).
- Orientation uses the standard sensor-orientation formula, untested on real hardware. Use `rot` to override and report what was needed.
- Landscape only (`sensorLandscape`). Android 16 ignores this lock on large screens (lint warning), which is the behaviour the product must plan for.
- Not yet run on any device. First on-device run may need fixes.
