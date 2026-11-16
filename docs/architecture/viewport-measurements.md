# Viewport feasibility measurements (Gate 4)

Outcome: the 3D viewport is rendered offscreen on its own thread and drawn by Compose as a bitmap (approach B). The other
approaches were built, measured and dropped; the spike code is gone. Everything here was measured on **one desktop**: a
Ryzen 9 9950X with an RTX 5080 (OpenGL, Skia Direct3D) and an AMD iGPU, JDK 25.0.1, Skiko 0.150.2, on a 3840x2160 @ 60 Hz
monitor at 150 %. Each run was 60 s after a 5 s warm-up. No display faster than 60 Hz was available, so the 144 Hz rows force
the limiter rate on the 60 Hz display. The synthetic scene had 156 bodies, 4x MSAA, one light and planar shadows.

## Approaches compared

| | A: `SwingPanel` + `AWTGLCanvas` | B: offscreen FBO, PBO readback, Compose bitmap | C: separate undecorated window |
|---|---|---|---|
| Menu, dialog, snackbar, FAB, scrim over the viewport | no by default | **all five correct** | none |
| CPU, 720p, one core | 137 % | **57 %** | 139 % |
| Longest frame interval | up to 183 ms, above 120 ms in 4 of 5 runs | **43 ms (Compose side 55 ms)** | 143 ms |

- **A** draws nothing over the canvas by default; every Skiko render API with `compose.interop.blending` or
  `compose.layers.type=WINDOW` left the GL canvas blank, or left the FAB, snackbar and scrim unable to overlap it, or (Direct3D
  with `WINDOW`) crashed the JVM in `skiko-windows-x64.dll`. A vsynced `SwapBuffers` also spins a core.
- **C** cannot show Compose overlays over it, and lost 3 frames to blank in 220-230 programmatic resizes (A and B lost none).
- **D** (Skia adopts the GL texture): the driver refuses a context shared with Skiko's from another thread (`GetLastError`
  170), and creating one inside the Skia callback crashed the JVM; `Canvas.recordingContext` does not exist in Skiko 0.150.2.

## Approach B in numbers

| Configuration | Frame interval p50 / p95 / p99 / max (ms) | CPU (% of one core) |
|---|---|---|
| Chrome only, no viewport | - | 35.4 |
| 1280x720, 60 Hz limiter | 16.67 / 16.72 / 17.18 / 30.4 | 57.5 |
| 1920x1080, 60 Hz limiter | 16.67 / 16.67 / 17.07 / 30.4 | 61.2 |
| 1280x720, 144 Hz, high-resolution timer | 6.94 / 6.95 / 7.02 / 38.1 | 77.6 |
| 1920x1080, 144 Hz, spin limiter | 6.94 / 6.95 / 7.30 / 42.7 | 78.5 |

- Per frame (p50, 720p / 1080p): the MSAA resolve and `glReadPixels` request 0.39 / 0.38 ms, the copy into Skia memory
  0.52 / 1.01 ms, GPU render 0.02 / 0.03 ms.
- Input to presented frame (synthetic events, 60 Hz display): p50 28.9 ms, p95 36.7, max 38.5 (a second run 38.3 / 48.1 /
  50.5). A and C measured 8-10 ms because they present through their own swap chain; B's extra latency is the readback frame
  plus the beat between the 60 Hz limiter and Skiko's vsync.
- `LockSupport.parkNanos` is bimodal here (about 1 ms or 14-15 ms) and held only about 64 Hz in a standalone JVM. The
  high-resolution waitable timer (`WindowsWaitableTimer`) overshoots about 0.5 ms p50 and holds 240 Hz at 13 % of a core,
  144 Hz at 15.6 % and 60 Hz at 0.9 %; the render and simulation loops use it.
- `-Dskiko.gpu.priority=integrated` moved Skiko's Direct3D renderer to the iGPU (67.9 % CPU, Compose frame p99 29.4 ms
  against 19.4 ms on the RTX); the OpenGL context stays on the RTX, and moving it needs a Windows graphics preference.

## The real fields

The numbers above are for a scene we made up. `RealFieldViewportTest` (`acceptance-tests`) opens real `SimulationSession`s
with Hornet's mapping on DECODE and BIOBUZZ and draws their `FieldDescription` and `WorldSnapshot` with the application's own
renderer, scene model and camera presets on a hidden OpenGL context at 1280x720 with 4x MSAA, into
`acceptance-tests\build\screens\real-*.png` (not committed). It ran on the RTX 5080 and is skipped without OpenGL 3.3.

**What the pictures changed.** DECODE's goal-side Top and Driver views were turned 36 degrees because the start heading in a
goal corner is the diagonal: `OrbitCamera.driverHeadingFor` keeps the start heading when it is square to the walls and
otherwise looks along -X, and the Chase camera stops short of scenery taller than 0.5 m. Specular glare and near-black
shadows are gone (translucent shadows with a stencil). The renderer adds no floor of its own under a field's floor slab,
which took GPU time per frame at 1080p from about 0.05 to 0.033 ms. AprilTags and the robot's alliance colour are drawn.

**Checked and fine:** scale of the field, robot and pieces; blue and red sides (pixel-counted in the test); nothing inside
out, mirrored or floating; no z-fighting at 1280x720 (tape 0.3 to 0.6 mm above the floor holds against a 24-bit depth
buffer from 6 m).

**What the fields could say better (not changed: they are frozen):**

- `FieldDescription` does not say which way the driver stands, so the UI guesses (above); a `driverHeadingRadians` would
  make it exact.
- Hornet's profile gives no chassis size, so the default 18 x 18 x 12 inch box is drawn without wheels, and a carried piece
  is drawn 4.5 cm above it (its magazine path rises to z = +0.26 m, the box top is at +0.152 m).
- Both floors are drawn as slabs (0.2 m and 0.5 m thick), because the fields draw what they collide with, so their edge shows
  at low angles. A visual-only flag on a solid would fix it. The HIVE tags face down, so no camera above the field sees them.

**Frame cost of the real DECODE scene.** RTX 5080, DRIVER preset, 4x MSAA, 600 frames after 60 of warm-up: 123 fixed and 42
moving instances, 5 draw calls, 18,500 triangles including the shadow pass, and no Java heap allocation per frame. The render
loop's body (draw, request the read-back, map the previous frame; not the copy into Skia memory or Compose) takes p50 / p99
0.167 / 0.328 ms at 1280x720, 0.272 / 0.427 ms at 1920x1080, 0.127 / 0.254 ms at 960x540 (50 % scale of 1080p) and
0.424 / 0.609 ms at 2560x1440; GPU time p50 is 0.019, 0.033, 0.015 and 0.051 ms. Instance, triangle and draw-call counts are
far too small to matter; what scales with the machine is pixels: the 4x MSAA target and its resolve, the read-back of 3.7 MB
(720p) or 8.3 MB (1080p) a frame, and the copy of it into a fresh Skia buffer that the render thread allocates every frame
(`OffscreenSink`; reusing the buffers would need Skia's ownership of the picture handled, so it was left). The levers are
already settings: the render scale (50 % cuts the GPU time by a third and the loop body by two thirds here) and, if wanted,
the multisampling.

## Not measured

- Any display above 60 Hz, and any hardware like the minimum laptop (4 cores, 8 GB, integrated GPU only).
- Real operating-system input delivery, window activation and focus (all input was synthetic).
- OpenGL on an integrated GPU: a hidden window placed on each of the two monitors was given an RTX 5080 context both times.
- A 60 s Compose run on the real DECODE scene: the frame-interval, CPU and latency tables were taken with the synthetic one.
- Controller hot-plug and axis data: GLFW 3.5.1 initialised without a window and was polled at about 120 Hz on its own
  thread (worst poll 2 ms), but no controller was attached.
