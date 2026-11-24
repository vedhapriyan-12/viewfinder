# V1 Requirements (DRAFT for freeze at end of Phase 0)

Source: inferred from the technical-review brief. The original vision doc was not supplied. Replace/reconcile when it arrives.

## In scope (proposed)

- Live full-screen viewfinder from the phone camera, landscape-first, tablet/foldable-safe layouts.
- Virtual setup: cinema camera + sensor mode + lens (prime/zoom) + focal length + T-stop.
- FOV match: choose the physical camera / zoom ratio that reproduces the virtual horizontal FOV; show native vs. digital-crop warning.
- Frame guides: common aspect ratios, custom ratio, safe areas, center mark, grids.
- Anamorphic: squeeze 1.25 / 1.33 / 1.5 / 1.65 / 1.8 / 2.0 / custom; live desqueeze.
- DOF readout: near / far / total / hyperfocal, configurable CoC, T-stop to f-number conversion.
- Gear catalog (offline, bundled, with sources) + custom user gear.
- Presets / projects / shot log with still capture and metadata.
- Settings: units, CoC defaults, guide appearance, night (red) mode.
- Per-device FOV calibration.
- Export/import of user data (JSON). Fully offline.

## Optional in V1 (decide at Phase 5)

Level (horizon), zebra, false color, focus peaking (indicative only; phone-processed image, not the cinema camera's signal).

## Out of scope for V1

Video recording, LUT import, cloud sync, accounts, analytics/crash SDKs, HDR/10-bit preview, microphone permission.

## Non-functional targets (to be confirmed by spike)

| Metric | Target |
|---|---|
| Sensor-to-screen latency | < 2 frames at 30 fps |
| Preview fps | 30 default, 60 opt-in, no sustained drops |
| FOV prediction error (after calibration) | within about 2% horizontal |
| Battery | measured in spike; budget set after baseline |
| Thermal | degrade analysis before preview on `THERMAL_STATUS_MODERATE`+ |
| Offline | 100% of V1 features work without network |

## Open product questions

1. Monetisation (none / one-time / free)?
2. Which cameras/lenses must ship in the seed catalog (initial list)?
3. Languages beyond English?
4. Required minimum HAL level, or block unsupported devices?
