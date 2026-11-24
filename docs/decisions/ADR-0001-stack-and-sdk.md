# ADR-0001: Stack, SDK levels, DI

Status: Accepted (approved with the technical review; items 3-4 below are defaults, revisit in Phase 0 if the spike disagrees)

## Decisions

| Area | Decision |
|---|---|
| Language/UI | Kotlin, Jetpack Compose (single activity, Navigation Compose) |
| Camera | Camera2 directly, behind a `CameraController` interface |
| Rendering | Custom EGL render thread, OpenGL ES 3.0, `SurfaceView` output, `samplerExternalOES` input |
| Persistence | Room (KSP): read-only prepackaged `catalog.db` + writable `user.db`; Preferences DataStore for settings |
| Concurrency | Coroutines/Flow for state; camera on its own `HandlerThread`; GL on its own thread |
| DI | Manual `AppContainer` with constructor injection (no Hilt in V1) |
| minSdk | 29 (Android 10). `CONTROL_ZOOM_RATIO` on API 30+, `SCALER_CROP_REGION` fallback on 29 |
| compileSdk/targetSdk | 36 minimum. Re-check in Phase 0 whether API 37 is stable and the current Play target-level deadline |
| Modules | `:app` + pure-JVM `:core:optics`. More modules only if justified |
| Dependencies | androidx core/lifecycle/activity-compose/navigation-compose/room/datastore only. No analytics, crash or paid SDKs |

## Assumptions to confirm (not yet answered by the product owner)

1. V1 has no video recording (stills + metadata only).
2. Tablets/foldables are supported at layout level (do not hard-lock orientation; Android 16 ignores locks on large screens for targetSdk 36 apps, verify).
3. Product vision / V1 feature list document has not been supplied; requirements are a draft inferred from the brief.
