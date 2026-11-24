# Viewfinder

Android director's viewfinder: uses the phone camera to simulate the framing of a chosen cinema camera, sensor mode and lens (FOV, aspect frame lines, anamorphic desqueeze, DOF). Offline-first, no paid SDKs, original UI/UX.

## Status

- Phase 0 (spike and decisions): done except the 60 minute soak (skipped by decision) and the FOV calibration rig (E6) and zoom-vs-GL-crop quality (E7). See `docs/phase0/SPIKE_RESULTS.md`.
- First product build runs on a Redmi Note 13 Pro 5G: live viewfinder, rig strip with pickers, lens barrel, frame lines, squeeze, sharp-zone ribbon, info panel.
- Catalog is a PLACEHOLDER, every entry tagged "unverified". Phase 2 replaces it with a sourced Room catalog.
- FOV matching is approximate until calibrated on a rig (`docs/phase0/CALIBRATION_PROTOCOL.md`).
- Framelines panel (17 ratios, custom, safe areas, multiple at once), vertical zoom rail with presets and pinch, and project settings that are saved between launches: see `docs/changes/2026-11-24-framelines-and-zoom.md`.
- Not built yet: multiple named projects, still capture, portrait layout, Room database.

## Layout

| Path | Purpose |
|---|---|
| `app/` | The Android app (Compose UI, camera + GL engine, placeholder catalog) |
| `core/optics/` | Pure Kotlin optics maths (FOV, DOF, framing, zoom limits, frame guides). 40 unit tests here, plus 32 in `app/` for framelines, project saving and zoom maths |
| `spike/` | Throwaway Phase 0 test app (own Gradle build, not part of the root build) |
| `docs/decisions/` | Architecture decision records |
| `docs/phase0/` | Requirements draft, spike plan and results, calibration protocol, device matrix, test vectors |
| `results/` | Raw measurements and screenshots from real devices |
| `tools/` | Helper scripts (PC-side soak observer) |
| `env.ps1` | Session-only environment (JDK 21, Android SDK, Gradle under `D:\Android`) |

## Build and run

```powershell
. D:\viewfinder\env.ps1
cd D:\viewfinder
.\gradlew.bat :core:optics:test        # unit tests
.\gradlew.bat :app:assembleDebug       # build the app
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Xiaomi phones show an "Install via USB" box on every install: tap the grey **Install** button (not the blue Deny, which has a countdown) with the notification shade closed.

## Phase map

0 Spike (done, partial) -> 1 Foundation -> 2 Optics core + catalog -> 3 Camera/render engine -> 4 Viewfinder UI -> 5 Tools -> 6 Presets/projects/stills -> 7 Hardening/beta -> 8 Release.
Phases 1 to 4 have been started together in the first product build; the remaining work in each is listed in the status above.
