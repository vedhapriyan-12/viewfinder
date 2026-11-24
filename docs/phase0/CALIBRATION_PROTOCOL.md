# FOV Calibration Protocol (Phase 0 experiment E6, and basis for in-app calibration)

Purpose: measure the real horizontal FOV delivered by each phone camera/stream and compare with the value predicted from Camera2 metadata.

## Predicted FOV

Preferred: `LENS_INTRINSIC_CALIBRATION` fx (px) -> `HFOV = 2*atan(Wpx / (2*fx))`, with Wpx the pixel width of the intrinsic reference (active array, adjust if the stream is cropped).
Fallback: `HFOV = 2*atan(W_mm / (2*f_mm))` from `SENSOR_INFO_PHYSICAL_SIZE` (width) and `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`.
A stream aspect narrower than the active array usually keeps the width (verify per device).
Stabilisation off: `CONTROL_VIDEO_STABILIZATION_MODE_OFF`, `LENS_OPTICAL_STABILIZATION_MODE_OFF` where available.

## Measured FOV (rig)

1. Flat wall chart with a horizontal ruler (or two markers) of known width `Wc`, at least 1 m wide; mark center.
2. Phone on a tripod, sensor plane parallel to the wall (check with a level), centered on the chart.
3. Measure distance `D` from the phone's sensor plane (approximate with the lens position) to the wall. Use 2 distances (for example 1.5 m and 2.5 m) to detect offset error.
4. Spike app: zoom ratio 1.0, stabilisation off, fixed focus at distance, record the left/right edges of the visible frame on the wall (use the screen grid in the spike overlay and read the ruler).
5. Visible width `Wv` on the wall -> `HFOV_measured = 2*atan(Wv / (2*D))`.
6. Repeat for each physical camera and for zoom ratios 1x, 2x (check linearity of `zoom ratio`).
7. Record: device, camera ID, physical ID, stream size, `HFOV_pred`, `HFOV_meas`, error %.

Error budget: distance error of 1 cm at 2 m is about 0.5%. Keep total rig error below 1%.

## In-app calibration (design input for Phase 4)

User frames a known width at a known distance; app derives an effective focal-length correction factor `k = tan(HFOV_meas/2) / tan(HFOV_pred/2)`, stores it per camera/stream in the user DB, and applies it to all subsequent FOV matching.

## Pass criteria

Error within about 2% on at least 2 of 3 devices after the correction. If not met, label FOV matching "approximate" and require calibration before first use.
