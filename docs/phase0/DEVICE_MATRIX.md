# Device Matrix (to be filled in E1)

Target: 4-5 physical devices covering GPU vendors, OEM camera stacks, and API levels. No devices selected yet.

| # | Slot | Why | Device (model) | Android/API | GPU | Owner | Probe JSON |
|---|---|---|---|---|---|---|---|
| 1 | Pixel (recent) | Reference Camera2 behaviour | TBD | | | | |
| 2 | Samsung flagship | Largest OEM, restricts physical IDs | TBD | | | | |
| 3 | Mid-range Snapdragon (Adreno) | Perf/thermal floor | Xiaomi Redmi Note 13 Pro 5G (2312DRA50I) | 16 / API 36 | Adreno 710 (ES 3.2) | project owner | `results/redmi-note-13-pro-5g/files/probe_2312DRA50I.json` |
| 4 | MediaTek/Mali device | GPU variance | TBD | | | | |
| 5 | Tablet or foldable | Layout, orientation, large-screen behaviour | TBD | | | | |
| 6 | Oldest supported (API 29) | minSdk path (`SCALER_CROP_REGION` fallback) | TBD | | | | |

## Per-device facts to record (from the probe)

HAL level, logical/physical IDs exposed, zoom ratio range, physical focal lengths and sizes, intrinsic calibration present, max `SurfaceTexture` preview size, 60 fps support, stabilisation modes, thermal behaviour in 60 min soak.
