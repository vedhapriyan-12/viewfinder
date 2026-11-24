# Optics Test Vectors (reference values for `:core:optics` unit tests)

Computed independently with double-precision arithmetic (PowerShell). Tolerance: 1e-6 relative unless noted. Sensor sizes below are illustrative, not catalog data.

## Horizontal AOV = 2*atan(squeeze * w / (2 f)), degrees

| w (mm) | f (mm) | squeeze | HAOV |
|---|---|---|---|
| 36.0 | 50 | 1.0 | 39.5978 |
| 24.9 | 50 | 1.0 | 27.9646 |
| 24.9 | 50 | 1.33 | 36.6466 |
| 24.9 | 50 | 2.0 | 52.9466 |

Vertical AOV (h = 13.5, f = 50): 15.3769 (unaffected by squeeze).

## Derived

| Quantity | Value |
|---|---|
| Full-frame diagonal | 43.2666 mm |
| Crop factor, 24.9 x 14.0 mm | 1.5146 |
| Desqueezed aspect, 24.9/14.0 at 2.0x | 3.5571 |
| T2.0 to f-number with tau = 0.9 (N = T*sqrt(tau)) | 1.8974 |
| Phone-match zoom ratio: target f=50, w=24.9; phone f=5.6, W=7.0 (tangent ratio) | 2.5100 |

## DOF (mm; H = f^2/(N c) + f; near = s(H-f)/(H+s-2f); far = s(H-f)/(H-s), infinite if s >= H)

| f | N | c | s | H | near | far |
|---|---|---|---|---|---|---|
| 50 | 2.8 | 0.030 | 3000 | 29811.905 | 2729.456 | 3330.077 |
| 35 | 2.0 | 0.025 | 2000 | 24535.000 | 1851.502 | 2174.395 |
| 100 | 4.0 | 0.030 | 20000 | 83433.333 | 16144.656 | 26274.304 |
| 100 | 4.0 | 0.030 | 90000 | 83433.333 | 43294.208 | infinity (s >= H) |

## Property tests

- AOV strictly decreases as f increases; strictly increases as w or squeeze increases.
- near < s < far for s < H; far is infinite for s >= H.
- DOF total strictly increases with N and c, decreases with f.
- Squeeze 1.0 reproduces the spherical result exactly.
