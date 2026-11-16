# Viewport measurements

Why the 3D viewport is an offscreen render drawn by Compose as a bitmap, with the numbers behind that choice and the gaps in them; read it before changing how the viewport is drawn or paced.

**Decision: the viewport renders offscreen on its own thread (FBO, PBO readback) and Compose draws the bitmap (approach B).** The other approaches were built, measured and dropped.

Setup, the same for every number below: one desktop (Ryzen 9 9950X, RTX 5080 on OpenGL with Skia on Direct3D, plus an AMD iGPU), JDK 25.0.1, Skiko 0.150.2, a 3840x2160 @ 60 Hz monitor at 150 %. Runs were 60 s after a 5 s warm-up. No display above 60 Hz was available, so the 144 Hz rows force the limiter rate on the 60 Hz display. The synthetic scene had 156 bodies, 4x MSAA, one light and planar shadows.

## Approaches compared

| | A: `SwingPanel` + `AWTGLCanvas` | B: offscreen FBO, PBO readback, Compose bitmap | C: separate undecorated window |
|---|---|---|---|
| Menu, dialog, snackbar, FAB, scrim over the viewport | no by default | **all five correct** | none |
| CPU, 720p, one core | 137 % | **57 %** | 139 % |
| Longest frame interval | up to 183 ms, above 120 ms in 4 of 5 runs | **43 ms (Compose side 55 ms)** | 143 ms |

- **A** draws nothing over the canvas by default. Every Skiko render API tried with `compose.interop.blending` or `compose.layers.type=WINDOW` left the canvas blank, or left the FAB, snackbar and scrim unable to overlap it, or (Direct3D with `WINDOW`) crashed the JVM in `skiko-windows-x64.dll`. A vsynced `SwapBuffers` also spins a core.
- **C** cannot show Compose overlays and lost 3 frames to blank in 220-230 programmatic resizes (A and B lost none).
- **D** (Skia adopts the GL texture) is impossible: the driver refuses a context shared with Skiko's from another thread (`GetLastError` 170), creating one inside the Skia callback crashed the JVM, and `Canvas.recordingContext` does not exist in Skiko 0.150.2.

## Approach B in numbers

| Configuration | Frame interval p50 / p95 / p99 / max (ms) | CPU (% of one core) |
|---|---|---|
| Chrome only, no viewport | - | 35.4 |
| 1280x720, 60 Hz limiter | 16.67 / 16.72 / 17.18 / 30.4 | 57.5 |
| 1920x1080, 60 Hz limiter | 16.67 / 16.67 / 17.07 / 30.4 | 61.2 |
| 1280x720, 144 Hz, high-resolution timer | 6.94 / 6.95 / 7.02 / 38.1 | 77.6 |
| 1920x1080, 144 Hz, spin limiter | 6.94 / 6.95 / 7.30 / 42.7 | 78.5 |

- Per frame (p50, 720p / 1080p): MSAA resolve and `glReadPixels` request 0.39 / 0.38 ms, copy into Skia memory 0.52 / 1.01 ms, GPU render 0.02 / 0.03 ms.
- Input to presented frame (synthetic events, 60 Hz display): p50 28.9 ms, p95 36.7, max 38.5 (second run 38.3 / 48.1 / 50.5). A and C measured 8-10 ms because they present through their own swap chain; B adds the readback frame and the beat between the 60 Hz limiter and Skiko's vsync.
- **The render and simulation loops pace with `WindowsWaitableTimer`, not `LockSupport.parkNanos`.** `parkNanos` is bimodal here (about 1 ms or 14-15 ms) and held only about 64 Hz in a standalone JVM. The timer overshoots about 0.5 ms p50 and holds 240 Hz at 13 % of a core, 144 Hz at 15.6 % and 60 Hz at 0.9 %.
- `-Dskiko.gpu.priority=integrated` moved Skiko's Direct3D renderer to the iGPU (67.9 % CPU, Compose frame p99 29.4 ms against 19.4 ms on the RTX). The OpenGL context stays on the RTX; moving it needs a Windows graphics preference.

## The real fields

`RealFieldViewportTest` (`acceptance-tests`) opens real sessions with Hornet's mapping on DECODE and BIOBUZZ and draws their `FieldDescription` and `WorldSnapshot` with the application's own renderer, scene model and camera presets on a hidden OpenGL context (1280x720, 4x MSAA) into `acceptance-tests\build\screens\real-*.png` (not committed). It ran on the RTX 5080 and is skipped without OpenGL 3.3.

**Decisions it drove:**

- DECODE's start heading in a goal corner is the diagonal, which turned the goal-side Top and Driver views 36 degrees. `OrbitCamera.driverHeadingFor` keeps the start heading when it is square to the walls and otherwise looks along -X.
- The Chase camera stops short of scenery taller than 0.5 m.
- Shadows are translucent (stencil), which removed specular glare and near-black shadows.
- The renderer adds no floor of its own under a field's floor slab, which cut GPU time at 1080p from about 0.05 to 0.033 ms.

**Checked and fine:** scale of field, robot and pieces; blue and red sides (pixel-counted); nothing inside out, mirrored or floating; no z-fighting at 1280x720 (tape 0.3 to 0.6 mm above the floor holds against a 24-bit depth buffer from 6 m).

**Gaps the fields could close (not changed: the fields are frozen):**

- `FieldDescription` does not say which way the driver stands, so the UI guesses (above). A `driverHeadingRadians` would make it exact.
- Hornet's mapping gives no chassis size, so the default 18 x 18 x 12 inch box is drawn without wheels, and a carried piece is drawn 4.5 cm above it (its magazine path rises to z = +0.26 m, the box top is at +0.152 m).
- Both floors are drawn as slabs (0.2 m and 0.5 m thick) because fields draw what they collide with, so their edge shows at low angles. A visual-only flag on a solid would fix it.
- The HIVE tags face down, so no camera above the field sees them.

**Frame cost of the real DECODE scene** (RTX 5080, DRIVER preset, 4x MSAA, 600 frames after 60 of warm-up): 123 fixed and 42 moving instances, 5 draw calls, 18,500 triangles including the shadow pass, no Java heap allocation per frame.

| Resolution | Render-loop body p50 / p99 (ms) | GPU p50 (ms) |
|---|---|---|
| 960x540 (50 % of 1080p) | 0.127 / 0.254 | 0.015 |
| 1280x720 | 0.167 / 0.328 | 0.019 |
| 1920x1080 | 0.272 / 0.427 | 0.033 |
| 2560x1440 | 0.424 / 0.609 | 0.051 |

The body is draw, request the read-back, map the previous frame; it excludes the copy into Skia memory and Compose.

- Instance, triangle and draw-call counts are too small to matter. What scales is pixels: the 4x MSAA target and its resolve, the read-back (3.7 MB at 720p, 8.3 MB at 1080p per frame) and the copy into a fresh Skia buffer that the render thread allocates every frame (`OffscreenSink`).
- Reusing those buffers would mean handling Skia's ownership of the picture, so it was left.
- The lever is the render scale setting: 50 % cuts GPU time by a third and the loop body by two thirds here. Multisampling is a `ViewportEngine` default (`DEFAULT_MSAA`), not a user setting.

## Not measured

- Any display above 60 Hz, and hardware like the minimum laptop (4 cores, 8 GB, integrated GPU only).
- Real operating-system input delivery, window activation and focus (all input was synthetic).
- OpenGL on an integrated GPU: a hidden window placed on each of the two monitors got an RTX 5080 context both times.
- A 60 s Compose run on the real DECODE scene: the interval, CPU and latency tables use the synthetic scene.
- Controller hot-plug and axis data: GLFW 3.5.1 initialised without a window and polled at about 120 Hz on its own thread (worst poll 2 ms), but no controller was attached.
