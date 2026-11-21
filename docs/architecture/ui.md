# Desktop UI

How the `desktop-ui` window is built and what its screens do; read it before changing the window, its input or its settings.

`desktop-ui` is Material 3 chrome (Compose Multiplatform) around a 3D viewport (LWJGL, OpenGL 3.3). It draws what a `SimulatorLink` reports and sends back what the driver does. It owns no simulation state and depends only on `sim-contracts` and `platform`. Kotlin is for Compose and its state holders; the renderer, the input logic and the host seam are Java. The window has no `main`: the real one is `app`'s ([app.md](app.md)).

```
.\gradlew.bat :desktop-ui:runFake      window on the FAKE host (canned OpModes, no robot code); interface work
.\gradlew.bat :desktop-ui:runFake --args="--no-focus --practice --exit-after=8 --settings=C:\temp\ui.properties"
                                       scripted: no focus stolen, opens the default fake session, closes itself
.\gradlew.bat :desktop-ui:check        the module's tests
```

## The host seam

`App.run(AppHost host, String[] args)` starts the window; `AppHost` is everything real the window needs:

| Method | Gives | Rule |
|---|---|---|
| `openLink()` | the `SimulatorLink`; may start a worker | Can take seconds and fail with an `IOException` whose message is for people. `AppServices` calls it off the UI thread (`link-opener`). Until it arrives a requested session waits ("Connecting"); on failure Simulator and Projects show the message with **Try again**. A link that arrives after the window closed is closed at once. |
| `preparer()` | the `ProgramPreparer` (`inspect`, `prepare`) | Runs the project's own Gradle build. |
| `isFake()` | whether nothing real runs | The title and a banner on every screen say FAKE HOST. |

The fake host (`FakeApp`, `FakeAppHost`, `FakeSimulatorLink`) lives in the module's **test fixtures** (`src\testFixtures`); no other module uses them and nothing shipped contains them. In the fake preparer a folder name containing `broken` fails its build and `no-sdk` finds no Android SDK, to show the failure screens. VS Code task: `ftc-sim: run UI (fake host)`.

## Shell and screens

The window is **project-first**: a person opens the folder of their robot code and everything follows; a built program is the application's private detail.

- A navigation rail lists **Projects, Controls, Simulator, Settings** (Simulator is `Destination.PRACTICE` in code), with Back above it. Ctrl+1 to Ctrl+4 go to the destinations in rail order, Alt+Left goes back.
- `Navigator` keeps each visited place once, so Back always leads somewhere new.
- Only the shown destination is composed, so screen state lives in `AppUi` and session, viewport and settings in `AppServices`. Leaving Simulator does not stop the run and leaving Projects does not stop a build.

### Writing the text

Few words, on purpose: a sentence appears only for an error, an empty state or a real danger.

- Labels and buttons first. No "so that..." sentences, no pointers to other screens, no repeats of what the screen shows, no full stop at the end of a line.
- An error says what is wrong and, if the person can act, what to do, in one line; detail goes behind **Show details**.
- One word per thing: **Project** (the team's folder), **Build**, **Setup** (field, alliance, start position), **Simulator**, **Code** (telemetry, hardware, console), **OpMode**.
- Session, worker, preparer, import and Gradle never appear in student-facing text, except in the build log and console.
- Every control is a standard M3 component (Tab and Enter work); icon-only buttons have a tooltip (`Tip`) and a description.

### Projects and Settings

- **Projects**: **Open project** (a folder chooser, the one with `settings.gradle`) and the ten newest recent projects. Choosing the folder is the consent to build it: there is no trust dialog (`Request.trusted` stays in the contract, always true from here). The build view, the game question and failures live here too.
- `AppUi.openFolder` gives a project to `ProjectRunner`, refuses a built program with what to open instead, refuses anything else with the reason, and refuses a second project while a build runs.
- **At start** the application opens the last project as Open project does, then shows the Simulator; `--no-last-project` skips it. If the folder is gone, the window stays on Projects with a message.
- **Settings**: render resolution (50, 75, 100 %), frame cap (60, 90, 144; never above the display), the performance overlay (off) and the settings file's location.

### The flow of a project

`ProjectRunner` is the only place that takes a person from a folder to a moving robot (`ProjectActivity`: `Idle`, `Building`, `NeedsGame`, `Failed`). Its `messages` flow is for what the person should read (snackbar); `problems` is for what stays wrong (Problems tab).

1. **Build.** `PrepareModel` holds the build, so the UI never waits on the preparer and the build outlives the screen. The window keeps the newest 2000 log lines (`OutputLog`); the build log file has everything.
2. **Game.** A project with no remembered run here is asked once (BIOBUZZ or DECODE, newest preselected). Any other starts with its last run. The settings form is filled while the build runs, so what the person changes meanwhile is what starts.
3. **Start.** The session opens with the request the form describes (no robot in it: the simulator builds the robot of the field, [fixed-robots.md](../robots/fixed-robots.md)), then the Simulator is shown. Nothing about the robot is asked or read from the project. A build and a running session never touch each other.

File choosers and the build-log opener sit behind `FileChooser` and `LogOpener`; tests use fakes, so the two real ones are untested. The folder chooser is Swing's (the native one cannot pick a folder).

### Restart code

Nothing watches the source. After editing, **Restart code** (Simulator top bar; off during a build) calls `ProjectRunner.restart()`:

- It builds what is on disk with the same preparer as Open project, then starts the session again with the new program, keeping field, alliance, start pose, seed and speed. It does not ask and does not leave the Simulator.
- A failure is a snackbar "Build failed" plus a Problems entry with the build log's path; the running session stays as it was.
- **The run controls wait, in one place** (`waitingFor`, with `Controls.of` taking its answer): while the code builds for a restart, while a hold is on the session, and while a session opens, the main button is a disabled **Restarting**, **Resetting** or **Starting** with a spinner, and Init, Start, Stop, Reset world, the OpMode menu and the match controls are off. Nothing the driver could start is ever shown on a session that is about to be replaced or that the application is using. The one hold (`PracticeSession.holds`) is Reset world (`RESETTING`, until the new session reports Idle, because the simulator answers with `opened` before the robot program has finished stopping).
- Unchanged source is not rebuilt; source that changes during a build is built once more.
- The runner keeps the last two programs of a project ([app.md](app.md)).

## The Simulator screen

- **Top bar**: an OpMode menu (the last choice, or the autonomous of the last match armed, is kept as `project.N.opMode`: `OpModeMemory`); one main button, **Init**, **Start** or **Stop**, enabled as the Driver Station enables them (`Controls.of` is the only place that says so); a **start position** menu (the field's poses; choosing one restarts the session from it); **Open dashboard**, shown only while `SessionStatus.dashboardUrl` is not empty (a program with Panels), which opens it in the browser through the `UrlOpener` seam (`AwtUrlOpener` uses `Desktop.browse`; tests use `FakeUrlOpener`, like `LogOpener`; a refusal is a snackbar); **Reset world** (stops the code and puts robot and field back in the same session, at once, without a question: `PracticeSession.reset`) with an arrow for **Restart code**; a **more** menu with Match, pause and speed (0.25x to 4x).
- **Match.** The window has no clock: countdown and period come only from `SessionStatus.match`, so pause and speed move them. A scoreboard over the viewport shows state, seconds left and the final score.
- **Viewport**: field, robot (a box in its alliance colour with a yellow marker on its front edge; the BIOBUZZ robot is its CAD ([biobuzz-cad](../robots/biobuzz-cad.md)): under 30 thousand triangles of fitted simple shapes in static GPU buffers, lexan drawn translucent, posed from the turret body and the joint bodies of the snapshot, with a box and solids as the fallback when the model is not in the build), pieces and AprilTags, with camera presets floating over it. Dragging leaves any preset for Free: left drag orbits, right or middle drag pans, the wheel zooms.

  | Preset | Stands |
  |---|---|
  | Top, Driver | At the driver's end: behind the robot along its heading when it starts square to the walls (BIOBUZZ, DECODE's audience side); at the audience end looking along -X when it starts in a corner facing the diagonal (DECODE's goal side) |
  | Chase | Behind the robot; stops in front of anything taller than 0.5 m (a goal, a HIVE), and goes round to the nearest clear side if that would leave it nearer than 1.1 m |

- **Details panel**: hidden until **Details** (bottom right of the viewport); floats over it and takes no robot keys. Tabs: **Code** (Driver Station telemetry, hardware readouts, console), **Score**, **Problems** (words, never colour alone; an error raises a snackbar with **Show**), **Setup**.
- **Status strip**: session state, simulated time, speed kept against speed asked (red when the machine cannot keep up), problem count, viewport frame rate. The performance overlay (Settings) adds frame interval, CPU and GPU render cost, and frame and snapshot rates.
- **Refusals and failures.** A refused command (`Listener.refused`) is a snackbar and the session goes on unchanged. Only `failed` ends a session ("The session could not go on", with **Try again**, which asks for the same session on a new link, and **Change the setup**).
- A reset is answered with `opened`, so the window starts the world again (the match is gone); the picked OpMode is kept if the session still has it.

## Link, session and state

`PracticeSession` exists before any link. The application opens the link off the UI thread and hands it over with `attach` (a session asked for earlier opens at once); `detach` drops a replaced link and nothing it says afterwards is heard.

- It registers as the link's `Listener` once per link, asks for a session with `open(request)` (a new request replaces the open one) and turns replies into separate `StateFlow`s plus a `refusals` event stream.
- Each flow changes only as often as its content, so the 30 Hz status wakes only composables that show something that changed. The contracts are declared stable to the Compose compiler (`compose-stability.conf`).
- The first snapshot, scene and status of a new session replace the old ones. An `opened` for an already open session (a reset) is handled the same way and the gamepads are sent again.
- **A session that replaces another goes to a fresh worker.** `AppServices.openSession` (Open project, Restart code, Apply and restart) drops the link, which closes its worker, and opens another. A worker keeps about 20 MB of every world it held, so a team restarting all day would reach a gigabyte (measurements: [app.md](app.md)). The cost is a few seconds with the viewport saying it is connecting.

## Threads and locks

| Thread | Does |
|---|---|
| link dispatcher | The link's listener calls; they reach the rest through thread-safe holders (`ViewportFeed`, the `StateFlow`s) |
| preparer's own | Calls back into `PrepareModel` |
| AWT event queue | Compose and input; file-system work goes to `Dispatchers.IO` |
| `render-loop` | Draws the viewport |
| GLFW thread | The hidden context window and joystick polling |
| `settings-writer` | Writes settings |

The window never blocks on any of them.

**Lock order: `ViewportInput`, then `GamepadRouter`, then `HeldKeys`.** `HeldKeys` and `OrbitCamera` call out only after releasing their own lock, or to something that never blocks, so the order cannot invert. `PracticeSession` calls the link's `addListener` and `open` under its own lock (they only queue, so a requested session is always opened by an already-listened-to link and requests arrive in order); other commands read the link under the lock and call it outside, and its listener methods store under the same lock.

### Viewport

The render thread draws into an offscreen framebuffer, reads it back and Compose shows it as a bitmap, so menus, dialogs and snackbars overlap it ([viewport-measurements.md](viewport-measurements.md) says why not a native canvas). The renderer is described in the Javadoc of `ui.render` and `ui.viewport`.

- The loop interpolates one snapshot interval behind the newest and draws only when something changed, so a world at rest costs no render time.
- AprilTags detected in the Limelight's latest completed frame have bright green outlines, including tags on moving
  bodies. Detection changes redraw even a stationary world; the UI never recomputes camera visibility.
- Field parts keep their RGB colour and opacity separately. Translucent panels are depth-tested and blended back to front
  after opaque geometry and shadows, without writing depth or casting opaque shadows; pieces stay visible through them.
- Besides boxes, cylinders and spheres, a field part may be a wedge (`Shape.Wedge`, a triangular prism with a unit mesh
  scaled by its half extents), used for the BIOBUZZ CELL gable.
- A pacer is used by one thread only: a changed frame cap puts a new pacer in place that the render thread takes before its next frame.
- The viewport is shown only while the Simulator is composed. On leaving, `ViewportEngine.setShown(false)` stops drawing, releases held keys and tells the controllers' gate; on return `ShownFrame` already holds the last picture, so it is never blank.

## Input

- Keys are captured only while the viewport (or the Controls key test area) has focus, the window is active and no menu or dialog is open. When any of those stops being true, held keys are released.
- Keys the map uses are consumed, so arrows do not move focus away from the robot. Tab, Escape, Enter, Space, Backspace, Delete and modifier keys can never be bound.
- **The robot is driven only while the Simulator is shown**, the window is active and no modal is open (`ViewportInput` gates keys and controllers). The Controls screen has its own live preview (`GamepadRouter.preview`) that ignores that gate.
- **Where a gamepad listens** is set per gamepad: Automatic (default), Keyboard, None, or a named controller. `GamepadRouting.resolve` is the pure rule, in order:
  1. Named controllers, matched by the model's GUID (found again in any joystick slot).
  2. The keyboard.
  3. Automatic gamepads take the first controller nobody has; gamepad 1 falls back to the keyboard when none is left.
  - A controller or the keyboard is never given to two gamepads: the second claim gets nothing and both cards say so.
  - A named controller that is not connected leaves its gamepad neutral, not a problem. Both gamepads always exist and are neutral when unassigned.
- **Controls screen**: a card per gamepad (source menu, live sticks, triggers, buttons, dead-zone sliders: `PadMapping`, round stick dead zone with the rest stretched to full, linear trigger dead zone, defaults 8 % and 2 %), and a keyboard card with a test area and the binding table (press a key's button, then the new key; a reserved or used key is refused with the reason).
- Default keys: WASD left stick, arrows right stick, Q E bumpers, Z C triggers, I J K L = Y X A B, T F G H = D-pad up, left, down, right, X V stick buttons, 1 2 3 Start Back Guide. Diagonals stay on the unit circle.
- `GamepadRouter` passes a state to the session only when it changed; the session sends both again when it (re)opens.

## Settings on disk

`%LOCALAPPDATA%\FTC-Sim\config\ui.properties` (`UserDirectories`), UTF-8 properties with `format=1`; `--settings=<file>` moves it.

| Keys | Hold |
|---|---|
| `display.*` | render scale, frame cap, overlay |
| `gamepad.N.*` | assignment kind, controller GUID and name, dead zones |
| `keyboard.<CONTROL>` | AWT key code (0 is unbound) |
| `project.N.*` | a recent project: folder, name, and if run, the field, alliance and start pose, and the OpMode the project opens with |

Obsolete keys (`recent.N.*`, `trusted.N`, `run.seed`, `run.randomSeed`, `lengthInches`) are ignored.

- Read once at start; written after every change on its own thread, coalesced, through a temporary file that then replaces the real one.
- A missing file gives the defaults. A nonsensical value is replaced by its default and the rest kept.
- A file that cannot be read, or has another `format`, is moved to `ui.properties.corrupt` (nothing is thrown away), defaults are used and a snackbar says so.
- A failed write says so and the settings keep working in memory.

## Tests

No test uses the real mouse, keyboard, focus or window. `PracticeSmokeTest` runs the real stack headless (services on the fake host, real screens drawn by `ImageComposeScene`, the real render thread on a hidden OpenGL context) and writes `build\screens\*.png`. Screen tests use synthetic events with bounded waits and no sleeps; pure parts are Compose-free unit tests. Tests needing OpenGL 3.3 are skipped, not failed, without it. Strategy and lists: `docs/testing.md`.

## Not done

Open points that matter to a driver are in `docs/roadmap.md`. Also:

- DECODE's polycarbonate walls are drawn opaque, so a low camera behind the near wall loses pieces against it. Translucency needs a sorted pass and a way to know which scenery is glass.
- No theming beyond the standard M3 schemes; the details panel cannot collapse or resize.
- Never tested with a controller attached: hot-plug and GUID matching on hardware are untested (router and Controls screen are tested with synthetic readings).
