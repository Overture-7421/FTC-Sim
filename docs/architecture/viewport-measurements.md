# Viewport feasibility measurements (Gate 4)

Outcome: the 3D viewport is rendered offscreen on its own thread and drawn by Compose as a bitmap
(approach B). The other approaches were built, measured and dropped. This file keeps the numbers that led to
that decision; the spike code that produced them is gone. "The real fields" below is about DECODE and BIOBUZZ themselves:
how they look in the viewport, what was fixed, and what the real scene costs to draw.

## Setup

- JDK 25.0.1, Gradle 9.2.0, Kotlin 2.4.21, Compose Multiplatform 1.12.2,
  Material 3 1.12.0-alpha03, Skiko 0.150.2, LWJGL 3.4.3, lwjgl3-awt 0.2.5.
- Ryzen 9 9950X, RTX 5080 (OpenGL, Skia Direct3D) and an AMD iGPU. All runs used the 3840x2160 @ 60 Hz, 150 %
  monitor, 60 s of measurement after a 5 s warm-up, machine load 10-34 % while measuring. The 280 Hz monitor was in use
  by someone else, so **no run on a display faster than 60 Hz exists**; 144 Hz rows below force the limiter rate on
  the 60 Hz display.
- Scene: 156 bodies (floor, walls, robot box, ~150 spheres in three sizes), 4x MSAA, one light, planar shadows.

## Approaches compared

| | A: `SwingPanel` + `AWTGLCanvas` | B: offscreen FBO, PBO readback, Compose bitmap | C: separate undecorated window | D: Skia adopts the GL texture |
|---|---|---|---|---|
| Menu, dialog, snackbar, FAB, scrim over the viewport | no by default; see below | **all five correct** | none | not testable |
| CPU, 720p, one core | 137 % | **57 %** | 139 % | n/a |
| Longest frame interval (render loop; B also on the Compose side) | up to 183 ms, above 120 ms in 4 of 5 runs | **at most 43 ms (Compose side 55 ms)** | 143 ms and 37 ms | n/a |
| Resize, blank frames in 220-230 programmatic resizes | 0 | **0** | 3 | n/a |

Approach A overlay matrix (every Skiko render API, `compose.interop.blending` on and off,
`compose.layers.type` COMPONENT and WINDOW):

- Default settings: nothing draws over the canvas.
- Direct3D with `compose.interop.blending=true`: all overlays draw, but the GL canvas stays blank.
- OpenGL or software with `compose.layers.type=WINDOW`: menu and dialog overlap; FAB, snackbar and scrim do not.
- Direct3D with `WINDOW`: native JVM crash in `skiko-windows-x64.dll` (`SkiaLayer.reshape` re-entering
  `PictureRecorder.finishRecordingAsPicture`).
- A vsynced `SwapBuffers` spins a core (swap interval 0 plus a 60 Hz limiter brought A to 57 %).

Approach D: Skiko's GL context can be reached by reflection and the `Drawable.onDraw` callback runs with the GPU
canvas, but the driver refuses to create a context shared with it from another thread (`GetLastError` 170,
busy), and creating one inside the Skia callback crashed the JVM with an access violation.
`Canvas.recordingContext` does not exist in Skiko 0.150.2.

## Approach B, 60 s per run

| Configuration | Frame interval p50 / p95 / p99 / max (ms) | CPU (% of one core) | Heap avg / max (MiB) | Working set (MiB) |
|---|---|---|---|---|
| Chrome only, no viewport | - | 35.4 | 24 / 37 | 297 |
| 1280x720, 60 Hz limiter | 16.67 / 16.72 / 17.18 / 30.4 | 57.5 | 64 / 97 | 442 |
| 1920x1080, 60 Hz limiter | 16.67 / 16.67 / 17.07 / 30.4 | 61.2 | 65 / 98 | 462 |
| 1280x720, 144 Hz spin limiter | 6.94 / 6.95 / 7.33 / 23.5 | 83.7 | 64 / 98 | 443 |
| 1280x720, 144 Hz high-resolution timer | 6.94 / 6.95 / 7.02 / 38.1 | 77.6 | 63 / 92 | 443 |
| 1920x1080, 144 Hz spin limiter | 6.94 / 6.95 / 7.30 / 42.7 | 78.5 | 65 / 98 | 475 |

Per-frame cost (p50):

| Stage | 720p | 1080p |
|---|---|---|
| MSAA resolve and `glReadPixels` request | 0.39 ms | 0.38 ms |
| Waiting for the pixel pack buffer to map | 0.02 ms | 0.03 ms |
| Copy into Skia memory | 0.52 ms | 1.01 ms |
| GPU render time | 0.02 ms | 0.03 ms |
| Compose draw callback | 0.03 ms | 0.02 ms |
| Render start to Compose draw | 18.0 ms | 18.4 ms |

Input to presented frame, synthetic events, 60 Hz display: p50 28.9 ms, p95 36.7, max 38.5 (a second run gave
38.3 / 48.1 / 50.5; at 125 % DPI 28.9 / 47.8 / 49.9). A and C measured 8-10 ms p50 because they present through their
own swap chain; the extra B latency is the readback frame plus the beat between the 60 Hz limiter and Skiko's vsync.

At 125 % and 150 % DPI B's offscreen target equals the viewport in device pixels exactly. Held keys are released
when a dialog opens and when focus moves away, keys are ignored while the viewport is unfocused, a 100 px drag
turns the camera 40 degrees, the wheel zooms; in B a Compose scrim also stops pointer events from reaching the
viewport, which in A and C only the application's own gate does.

## Timers

- `LockSupport.parkNanos` is bimodal on this machine: about 1 ms or 14-15 ms depending on the system timer period.
  A `parkNanos` limiter held only about 64 Hz in a standalone JVM.
- The high-resolution waitable timer (`WindowsWaitableTimer`) overshoots by about 0.5 ms p50 and holds 240 Hz at 13 %
  of a core, 144 Hz at 15.6 % and 60 Hz at 0.9 %. Render and simulation loops use it.

## GPU choice

`-Dskiko.gpu.priority=integrated` moved Skiko's Direct3D renderer to the AMD iGPU (B: 67.9 % CPU, Compose frame
p99 29.4 ms against 19.4 ms on the RTX). The OpenGL context from LWJGL stays on the RTX; putting it on the iGPU
needs a Windows graphics preference or a driver setting, which was not changed.

## The real fields

Everything above was measured on a scene we made up. `RealFieldViewportTest` (acceptance-tests) opens real
`SimulationSession`s with Hornet's mapping on DECODE and BIOBUZZ and draws their `FieldDescription` and
`WorldSnapshot` with the application's own renderer, scene model and camera presets on a hidden OpenGL context at 1280x720
with 4x MSAA, into `acceptance-tests\build\screens\real-*.png` (a build output, not committed). It was run on the RTX 5080
(OpenGL 3.3 core, driver 621.14) and is skipped where there is no OpenGL 3.3 context.

### What the first pictures showed, and what was done

| Seen | Cause | Done |
|---|---|---|
| DECODE, Top and Driver from the goal-side start: the field turned about 36 degrees on screen and the blue goal and robot cut off at the bottom; Driver stood behind the goal tower with the robot hidden. | The driver's direction was the start heading, which in a goal corner is the diagonal. | `OrbitCamera.driverHeadingFor`: a start square to the walls (BIOBUZZ, DECODE's audience side) keeps its heading; any other looks along -X, the audience's end in both games' frame. Top is 6.2 m so that the goal tops fit. |
| Chase from a goal start sat inside the goal tower (DECODE) or behind the wall with the robot half hidden (BIOBUZZ). | No notion of what stands between the camera and the robot. | The chase camera stops short of scenery taller than 0.5 m (goals, HIVEs), goes round to the nearest clear side when that would leave it nearer than 1.1 m, and comes back when behind is clear; walls and tape are looked over. |
| A bright glare gradient across the floor, as if wet. | One specular term (0.35) on everything. | Spheres 0.30, other solids 0.05, none on floors. |
| Shadows were near-black navy blobs, darker still where two overlapped. | Opaque decals in the renderer's own tile colour, which a field's grey floor hides. | Translucent (alpha 0.34) with a stencil, so a pixel is darkened once. |
| No tile seams on either field, so no cue for scale. | The field's floor slab covers the renderer's tiled floor. | A flat box as wide as the field with its top in the floor plane is recognised as the floor and drawn with 24 inch seams over its own colour. |
| No AprilTag was drawn (goal tags, the obelisk's three, BIOBUZZ's sixteen on the HIVEs). | Tags are not scenery. | Each tag is a black square with a white square and a black core on it, placed by its pose; tags that ride on a body are placed with that body. |
| The red alliance's robot was blue. | A constant. | The body takes its alliance's colour; the yellow marker stays on the front edge. |
| A hidden second floor was shaded under the field's own across the whole field. | The renderer always added its tiled floor. | Not added under a floor that covers the scene. GPU time per frame at 1080p with 4x MSAA went from about 0.05 to 0.033 ms, and at 720p from about 0.03 to 0.019 ms. |

### What the pictures show now

DECODE, from the audience (top and driver views): the 12 ft square with white launch-zone triangles meeting at the middle and a
small one at the audience wall, the loading-zone corners at the audience end, red and blue squares and lines on the
floor, the blue goal on the left and the red on the right, each with its ramp stepping along the side wall to a gate arm and
a small double strip, the obelisk outside the middle of the goal-side wall with a tag on the face that looks at the field,
a tag on each goal's front panel, eighteen purple and green pieces in six threes along the spike marks and three in each
loading zone, shadows under the pieces, and the 24 inch seams. The robot is an 18 inch box in its alliance's colour at its goal with the
yellow marker on its front edge. From the front of the robot at the goal the tag sits above it on the panel; the ramp's
steps, the gate zone strips and the tape are stable at close range.

BIOBUZZ, from behind the robot's own wall: its alliance's HIVE, nectar and tape on the near side and the other's on the
far side, the four FLOWERs with their rings, the pollen along the walls and in the loading zones, the frame with its legs
and the HIVE arms tilted at their stops. With three POLLEN set down beside the three staged NECTAR in the red HIVE's
audience CELL the HIVE tipped from -30 to +30 degrees and the pieces lay spilled on the floor beside it, as in the field's own tests.

Checked and fine: the scale of the field, the robot and the 5 inch pieces; blue and red sides (pixel-counted in the test);
purple and green, yellow, red and blue nectar; nothing inside out, mirrored or floating; no z-fighting at 1280x720 (tape
0.3 to 0.6 mm above the floor holds against a 24-bit depth buffer from 6 m).

### What the fields could say better (not changed: they are frozen)

- `FieldDescription` does not say which way the driver stands. DECODE's goal-side start poses face the diagonal, so the
  camera cannot take the driver's end from the start pose; the UI guesses (above). A `driverHeadingRadians` in the
  description would make it exact.
- Hornet's profile gives no chassis size, so the default 18 x 18 x 12 inch box is drawn, and its magazine path rises to
  z = +0.26 m from the box centre while the box top is at +0.152 m: a piece being carried is drawn 4.5 cm above the
  robot (`real-decode-carried.png`). There are no wheels either, so the box hovers 3 cm above the floor.
- DECODE's floor is a 6 m square slab 0.2 m thick with its top at z = 0, and BIOBUZZ's a 4.6 m square slab 0.5 m thick; both
  are drawn, because the fields draw what they collide with, so the field seems to stand on a slab whose edge and thickness
  show at low angles. A visual-only flag on a solid would let a field keep the collider and draw a thin floor.
- The HIVE tags face down (out of the underside of the CELL that faces up), as the field's own notes say, so no camera
  above the field sees them; it is the right place for the robot's camera to look for them.

### Frame cost of the real DECODE scene

RTX 5080, DRIVER preset, 600 frames after 60 of warm-up, one run at a time. The scene is 123 fixed and 42 moving instances
(the field's 117 solids and 6 tag layers; the robot, the obelisk and its tags, the gate arms and 26 pieces, two of them
carried by the robot), drawn in 5 draw calls with 18,500 triangles
including the shadow pass. "Loop body" is what the render loop does per frame (draw, request the read-back, map the frame
before) and so includes waiting for the previous transfer; it excludes the copy into Skia memory (0.5 ms at 720p and 1.0 ms
at 1080p, above) and Compose. GPU time is from a timer query around the draw with the frames kept apart.

| Setting | CPU submit p50 / p99 (ms) | GPU p50 / p99 (ms) | Loop body p50 / p99 (ms) | Java heap per frame |
|---|---|---|---|---|
| 1280x720, 4x MSAA | 0.005 / 0.051 | 0.019 / 0.020 | 0.167 / 0.328 | 0 B |
| 1280x720, no MSAA | 0.003 / 0.020 | 0.014 / 0.015 | 0.155 / 0.303 | 0 B |
| 640x360 (50 % of 1280x720), 4x MSAA | 0.002 / 0.014 | 0.011 / 0.012 | 0.041 / 0.190 | 0 B |
| 1920x1080, 4x MSAA | 0.004 / 0.014 | 0.033 / 0.034 | 0.272 / 0.427 | 0 B |
| 1920x1080, no MSAA | 0.003 / 0.010 | 0.019 / 0.020 | 0.251 / 0.366 | 0 B |
| 960x540 (50 % of 1920x1080), 4x MSAA | 0.003 / 0.013 | 0.015 / 0.016 | 0.127 / 0.254 | 0 B |
| 2560x1440, 4x MSAA | 0.005 / 0.011 | 0.051 / 0.052 | 0.424 / 0.609 | 0 B |

For a 4-core integrated-GPU laptop (not measured, see below): the instance count, the triangle count and the draw calls are
far too small to matter, and the renderer allocates nothing on the Java heap per frame. What scales with the machine is
pixels: the 4x MSAA target and its resolve, the read-back of 3.7 MB (720p) or 8.3 MB (1080p) a frame, and the copy of it into a
fresh Skia buffer of that size that the render thread allocates every frame (`OffscreenSink`; reusing the buffers would need
Skia's ownership of the picture handled, so it was left). The levers are already settings: the render scale (50 % cuts the
GPU time by a third and the loop body by two thirds here) and, if wanted, the multisampling. The hidden full-field floor
pass was the one cheap waste, and is gone.

## Not measured

- Any display above 60 Hz, and any hardware like the minimum laptop (4 cores, 8 GB, integrated GPU only).
- Real operating-system input delivery, real window activation and focus (all input was synthetic).
- OpenGL on an integrated GPU. It cannot be reached from here without changing Windows' graphics preference: a hidden
  window placed on each of the two monitors (one on the RTX 5080, one on the AMD iGPU) was given an RTX 5080 context both times.
- The 60 s run through Compose on the real DECODE scene. The renderer's cost above is far below the synthetic scene's
  (a tenth of a millisecond of loop body at 1080p), but the frame intervals, CPU and latency tables were taken with the
  synthetic one.
- Controller hot-plug and axis data: GLFW 3.5.1 initialised without a window and was polled at about 120 Hz on its
  own thread (worst poll 2 ms), but no controller was attached.
