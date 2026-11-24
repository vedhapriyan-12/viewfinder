# Framelines panel and zoom rail (2026-11-24)

## What changed

### Aspect-ratio framelines
- Panel with 17 presets, each with a to-scale preview, ratio and name. Cinema and film: 1.33 (4:3), 1.37 Academy, 1.66, 1.78 (16:9), 1.85, 2.00, 2.20 (70mm), 2.35, 2.39 (Anamorphic), 2.40, 2.55, 2.76 (Ultra Panavision). Other: 1:1, 4:5, 9:16, 3:2, 5:4.
- Any number of framelines can be on at once. The one switched on last is the active one: bright line, dim mask outside it, safe areas and centre mark. The others are thin dashed teal lines. Each has a label on a dark pill, placed clear of the controls.
- Safe areas 80 / 90 / 95 % (any combination) inside the active frameline, and an optional thirds grid.
- Custom ratio: coarse drag bar plus fine +/- steps (0.01), saved with the project, removable. Range 0.25 to 6.00, at most 12 per project. A drag bar was chosen over typing because an on-screen keyboard in landscape would cover the picture.
- The live picture is never altered by framelines: they are drawn over it.
- Everything is saved in the project settings (`files/projects/default.json`): enabled framelines in order, custom ratios, safe areas, thirds, rig, zoom mode.

### Zoom
- Vertical zoom rail on the right: current value, AUTO or MANUAL tag, log-scale track with presets 5x, 3x, 2x, 1x drawn at their real positions, smooth drag, tap-to-snap, and a "0.5x n/a" note where the phone cannot reach it.
- Pinch to zoom anywhere on the picture. Preset taps glide to the target.
- Auto (default) keeps the zoom matched to the chosen lens. Moving the rail or pinching switches to Manual, which shows what the camera sees at that zoom, cropped only to the frame shape. The Auto chip, touching the lens ruler, or choosing a lens returns to Auto. In Manual the lens ruler is dimmed and the readout says "manual zoom".
- Limits come from the camera itself (`CONTROL_ZOOM_RATIO_RANGE`): 1.0 to 10.0 on the test phone. Nothing is magnified on the GPU beyond that unless "Dig on" is switched on; then the track extends to twice the camera maximum, the extra stretch is amber, and a "Digital zoom, picture is softer" notice shows. Default is off.
- Zoom reaches Camera2 through the existing `engine.setFraming` path. The camera engine was not rewritten.

### Layout
Tools moved to a column in the left bar (they collided with frame labels at the top). Depth-of-field ribbon moved to the bottom left. Warnings stack above the readout. Controls respect the display cutout on whichever side it ends up. Landscape only.

## Files

New: `app/.../domain/Framelines.kt`, `app/.../domain/ProjectStore.kt`, `app/src/test/.../FramelinesTest.kt`, `ProjectStoreTest.kt`, `RigMathTest.kt`.
Changed: `core/optics/.../Optics.kt` (manual zoom, digital-zoom limits, pinch), `core/optics/src/test/.../OpticsTest.kt`, `app/build.gradle.kts` (test dependencies), `camera/PhoneCamera.kt` (use the camera's real minimum zoom), `camera/ViewfinderEngine.kt` (diagnostics), `domain/Catalog.kt`, `domain/Rig.kt`, `MainActivity.kt`, `ui/Overlays.kt`, `ui/Pickers.kt`, `ui/Theme.kt`, `ui/ViewfinderScreen.kt`, `ui/ViewfinderViewModel.kt`.

## Tests

Unit tests: 72 pass (40 optics, 32 app). They cover all 17 presets against a 16:9 and a very wide display (shape, containment, centring), safe-area maths, custom ratios, project save/load including damaged files, zoom clamping, digital-zoom limits, pinch.

On the phone (Redmi Note 13 Pro 5G, Android 16, landscape, debug builds driven by `--es vf_script`):
- All 17 aspect ratios: drawn shape matches the preset to 0.00 % error and touches the picture edge (two of them needed a slower retry, see below).
- Zoom: auto 50 mm requests 2.965x and Camera2 reports 2.96. Presets 1x, 2x, 3x, 5x reported by Camera2 as exactly 1.00, 2.00, 3.00, 5.00. 0.5x clamps to 1.00. 15x with Dig off stops at 10.00; with Dig on it is camera 10.00 times 1.5 digital. 20x with Dig on is camera 10.00 times 2.0.
- Persistence: framelines, a custom 2.10:1 ratio, safe areas, thirds and manual zoom all returned after force-stopping the app and relaunching with no script.
- Screenshots in `results/redmi-note-13-pro-5g/ui*.png`.

## Not verified on a device (be honest about it)
- Pinch-to-zoom, drag on the rail, taps on the panel and the custom-ratio bar. The phone blocks touch injection from the computer, so these were verified only through code review and the maths tests (`Framing.pinch`), not by touching the screen.
- The other landscape direction (top edge to the right) and display cutouts on that side.
- 0.5x: this phone exposes no ultrawide, so the path for a camera with a zoom minimum below 1.0 is unit-tested but not seen on hardware.

## Debug hook (debug builds only)
`adb shell "am start -n app.viewfinder/.MainActivity --es vf_script 'reset;frame=2.39,1.85;safe=90;zoom=2;digital=1;thirds=1;info=1;camera=generic-ff/3x2;lens=ana2-primes;focal=50;picker=frames'"`. Release builds ignore it.
