# Desktop UI

`desktop-ui` is the simulator's window: Material 3 chrome (Compose Multiplatform for Desktop) around a 3D viewport
(LWJGL, OpenGL 3.3). It draws what a `SimulatorLink` tells it and sends back what the driver does; it owns no simulation
state and depends only on `sim-contracts` and `platform`. Kotlin is used only for Compose and the state holders beside
it; the renderer, the input logic and the host seam are Java. The fake host that the window can be developed on lives in
the module's **test fixtures** (`src\testFixtures`, Gradle's `java-test-fixtures`), not in the shipped window: the
window has no `main` of its own, and the real one is `app`'s.

```
.\gradlew.bat :desktop-ui:runFake                              opens the window on the FAKE host (fake link, fake preparer, no robot code)
.\gradlew.bat :desktop-ui:runFake --args="--no-focus --practice --exit-after=8 --settings=C:\temp\ui.properties"
                                                               scripted launch: no focus stolen, opens the default
                                                               fake session, closes itself, keeps its settings elsewhere
.\gradlew.bat :desktop-ui:check                                the module's tests; nothing waits on real time to be right
```

## The host seam

The window is started by whoever has the real things to show: `dev.ftcsim.ui.App.run(AppHost host, String[] args)` (the
`app` module's `main` calls it; `App.run(host, options, started)` is the same with parsed options and a hook that runs
once the services exist). `AppHost` has these parts:

- `openLink()` returns the `SimulatorLink` and may start a worker process, so it can take seconds and can fail with an
  `IOException` whose message is for people. `AppServices` calls it **off the UI thread**, on a single thread named
  `link-opener`. Until the link is there, a session that is asked for waits for it ("Connecting" on the Simulator); when it
  cannot be had, the Simulator and Projects show the message with **Try again**. The link is closed when the window closes
  (a link that arrives after that is closed on the spot).
- `preparer()` returns the `ProgramPreparer` (`inspect` says what a folder is, `prepare` runs the project's own Gradle build).
- `mappings()` returns the `ProjectMappings` (`find`, `save`, `saveRunDefaults`; a file this version does not read is found
  with its `problem` and no mapping, and `save` copies it to `ftcsim.old.json` first). They touch the disk, so they are
  called off the UI thread.
- `isFake()` says that nothing real runs; the window then says FAKE HOST in its title and on a banner on every screen.

`dev.ftcsim.ui.FakeApp` (test fixtures) builds a `FakeAppHost` and calls `App.run`; that is what `:desktop-ui:runFake`
starts, for interface work (the VS Code task is `ftc-sim: run UI (fake host)`). The fake link (`FakeSimulatorLink` over
`FakeWorld`) has no robot program and no real field, so the window shows canned OpModes and a field that looks like nothing
in the manual. The fixtures are used by the tests of `desktop-ui` through `testFixtures`; no other module depends on them
and the shipped window, `app` and the worker contain none of it. In the fake preparer a folder name containing `broken`
fails its build and `no-sdk` finds no Android SDK, so the failure screens can be seen.

## The shell and the screens

The window is **project-first**: a person opens the folder of their team's robot code and everything else follows from it;
a built program is an implementation detail that the application keeps for itself.

A Material 3 navigation rail on the left lists **Projects, Robot, Controls, Simulator and Settings** with a Back
button above it (in the code the Simulator is `Destination.PRACTICE`). `Navigator` keeps the history as the places visited
before, each at most once, so Back always leads somewhere different from where you are. Ctrl+1 to Ctrl+5 go to the
destinations in rail order and Alt+Left goes back, from wherever the focus is. Only the shown destination is composed, so
each screen's own state lives outside the composition (`AppUi`) and the session, viewport and settings live in
`AppServices`; leaving Simulator therefore does not stop the run, leaving Projects does not stop a build, and coming back
shows each as it is.

### Writing the text

The window has few words on purpose. A student reads labels and buttons; a sentence appears only for an error, an empty
state or a real danger.

- Labels and buttons first. No sentence that explains why ("so that ..."), no pointer to another screen ("see the Robot
  page"), no message that repeats what the screen already shows, no full stop at the end of a line of text.
- An error says what is wrong and, when the person can do something about it, what, in one line; the detail goes behind
  **Show details**.
- One word for one thing: **Project** (the team's folder), **Build** (what turns it into a program), **Robot** (the
  `ftcsim.json`), **Motors** (the names the code asks the hub for), **Setup** (field, alliance, start position),
  **Simulator**, **Code** (telemetry, hardware, console) and **OpMode**.
- The words session, worker, preparer, import and Gradle are not used in what a student reads, except in the build log and
  the console, which show what the tools said.
- Every control is a standard M3 component (Tab and Enter work); icon-only buttons have a tooltip (`Tip`) and a description.

### The screens

- **Projects**: **Open project** (a folder chooser) and the **recent projects**, newest first (ten are kept). The folder to
  choose is the one with `settings.gradle`. Choosing it is the consent to build it: there is no trust dialog
  (`Request.trusted` stays in the contract and is always true from here). The build view, the question for the game and a
  failure also live here. `AppUi.openFolder` hands a **project** to the `ProjectRunner`, refuses a **built program** with
  the kind of folder to open instead and anything else with the reason; while a build is under way another project is
  refused and the build is left alone.
- **At start** the application opens the project that was open last time, exactly as Open project does (a changed source
  is built again, an unchanged one is not), unless `--no-last-project` is given, and shows the Simulator when it is done.
  If the folder is gone, or the project is no longer set up for the simulator (no usable `ftcsim.json`, and the Robot page
  not skipped), the window stays on Projects with a message.
- **Settings**: render resolution (50, 75 or 100 percent of the viewport's size), the frame cap (60, 90 or 144, never
  above the display), the performance overlay (off by default) and where the settings file is. **Robot** and **Controls**
  are below.

### The flow of a project

`ProjectRunner` is the one place that moves a person from a folder to a moving robot (`ProjectActivity`: `Idle`,
`Building`, `NeedsGame`, `Failed`). What the person should read goes out on its `messages` flow (a snackbar) and what stays
wrong goes to its `problems` flow (the Problems tab).

1. **Build.** `PrepareModel` holds the build and its end, so nothing waits for the preparer on the UI thread and the build
   outlives the screen. The window keeps the newest 2000 log lines (`OutputLog`); the build log file has all of it.
2. **Game.** A project with no remembered run on this computer and no `run.field` in its `ftcsim.json` is asked once for
   its game (BIOBUZZ or DECODE, the newest preselected). Every other project starts with what it was last run with, else
   what the `run` block says.
3. **Start.** The session starts with the project's `RobotMapping` in the `SessionRequest` and the Simulator is shown. A
   project **without** a usable `ftcsim.json` runs with `RobotMapping.empty(projectName)`: no motors mapped, so every motor
   the code asks for is unmapped, the wheels too, and a problem says so. A file that is there and is not a schema 2 file
   (an older version, or one that does not parse) is never read as a mapping and never overwritten silently: the project is
   treated as having no mapping, and saving first copies the file to `ftcsim.old.json`. The first time, the Robot screen
   is shown after the session opens; `skipRobot()` remembers the choice (`RecentProject.robotSkipped`).
4. **Robot file.** `saveRobot(mapping)` writes the whole mapping, shows the Simulator and restarts the code, which reads the
   file again. `saveDefaults` writes the Setup tab's field, alliance and start position into the `run` block. A build and a
   running session coexist: nothing in the build touches the session.

### Robot

The **Robot** screen (`MappingScreen`, `MappingModel`, package `dev.ftcsim.ui.robot`) is the mapping page and nothing else;
the same page maps the robot of a project that has no `ftcsim.json` and edits one that has. The robot itself is fixed by the
game (`robots/fixed-robots.md`, which also holds the mapping schema); the page only says which motor of the team's code is
which part of it. The person picks an OpMode, the app runs it up to Init to read the names it asks the hub for
(`HardwareScan`), and each detected motor is dragged, or clicked and assigned, onto a part. Only motors are mapped: servos,
sensors and pins are provided by the simulator. Hub and port are not asked: the design gives each motor the next free port.
What is wrong with a field is said under it in the contract's own words (`RobotMapping.problems`), and `blockedReason` says
why the button cannot be pressed. The button is **Create** (with **Skip**) for a project without a usable file and **Save**
for one with a file. The names the program asked for and the mapping does not place come from `SessionStatus.unmapped`
(wire v5, `architecture/link.md`); the window uses only the motors among them.

### Restart code

The robot code is the project's source on disk, and nothing watches it. A person edits, then presses **Restart code** in the
Simulator's top bar (off while a build is under way). `ProjectRunner.restart()` builds what is on disk now with the same
preparer as Open project and, when the build ends, starts the session again with the new program and the project's current
`ftcsim.json`, keeping the field, alliance, start pose, seed and speed that were in use. It does not ask and does not leave
the Simulator screen. A failure is a snackbar "Build failed" and an entry in the Problems tab (with the build log's path);
the session that was running stays as it was. A source that did not change is not built again; a source that changes while
the build runs is built once more. The runner keeps the last two programs of a project and deletes older ones
(`docs/architecture/app.md`).

File and folder choosers sit behind `FileChooser` and opening a build log behind `LogOpener`; tests use `FakeFileChooser`
and `FakeLogOpener`, so no dialog opens and no program starts, and the two real ones are untested for that reason.

## The Simulator screen

- **Top bar**: an OpMode menu (the last OpMode chosen, or the autonomous of the last match armed, is kept with the project
  as `project.N.opMode` and picked again by every session that still has it: `OpModeMemory`), one main button that goes
  **Init**, **Start**, **Stop** (enabled the way the Driver Station enables them; `Controls.of` is the one place that says
  so), a **start position** menu (the field's poses, then the team's **named positions** for the field, kept as
  `startPositions` in `ftcsim.json` so teammates share them; choosing one starts the session again from it), a **Reset
  world** button (stops the code and puts the robot and the field back at the start in the same session, at once and with
  no question: `PracticeSession.reset`) with an arrow for **Restart code**, and a **more** menu with Match, pause and speed
  (0.25x to 4x).
- **Match.** The window has no clock of its own: the countdown and the period come from `SessionStatus.match` alone, so
  pausing or changing the speed moves them with it. A scoreboard over the viewport shows the state, the seconds left and,
  once it ended, the final score.
- **Finding the hardware** (`HardwareScan`) sends Init for the chosen OpMode through `PracticeSession.init(name)`, waits for
  Init, sends Stop, then counts `SessionStatus.unmapped`. It follows `PracticeSession.openings`, a counter, because two
  sessions with equal descriptions are equal `SessionState`s and a flow would not tell them apart.
- **Viewport**: the field, the robot (a box in its alliance's colour with a yellow marker on its front edge), the pieces and
  the AprilTags where the field puts them, with camera presets floating over it. Top and Driver stand at the driver's end:
  behind the robot along its heading when it starts square to the walls (BIOBUZZ, DECODE's audience side), and at the
  audience's end, looking along -X, when it starts in a corner facing the diagonal (DECODE's goal side). Chase follows from
  behind and does not look through anything taller than half a metre (a goal, a HIVE): it stops in front of it, and where
  that would leave it nearer than 1.1 m it goes round to the nearest clear side. Dragging leaves any preset for Free.
- **Details panel**: hidden until the **Details** button at the viewport's bottom right is pressed; it floats over the
  viewport and takes no keys from the robot. Tabs: **Code** (Driver Station telemetry, hardware readouts, the console),
  **Score**, **Problems** (what went wrong, in words, never by colour alone; an error raises a snackbar with **Show**) and
  **Setup** (field, alliance, start position).
- **Status strip**: where the session is, the simulated time and the speed kept against the speed asked for (red when the
  machine cannot keep up), the problem count and the viewport's frame rate; the **performance overlay** (Settings) adds the
  frame interval, the render cost on the CPU and the GPU, and the rates of frames and snapshots.
- **Refusals and failures.** A command the link refuses (`Listener.refused`) is a snackbar with the message, and the session
  goes on exactly as it was. Only `failed` ends a session ("The session could not go on" with **Try again**, which asks for
  the same session on a link opened anew, and **Change the setup**). A link that cannot be had is said first, with **Try
  again**.
- A reset does not show the session opening: the worker resets the world in place and answers with `opened`, so the window
  starts the world again (the match is gone); the OpMode the driver picked is kept if the session still has it.

## Link, session and state

`PracticeSession` exists before any link does: the application opens the link off the UI thread and hands it over with
`attach` (a session asked for before then is opened on it at once; a link that is replaced is dropped with `detach` and
nothing it says afterwards is heard). It registers as the link's `Listener` once per link, asks for the session with
`open(request)` (a new request replaces the one that is open) and turns what comes back into separate `StateFlow`s plus the
`refusals` event stream. Each changes as rarely as its content, so the 30 Hz status wakes only the composables that show
something that changed (the contracts are declared stable to the Compose compiler, `compose-stability.conf`). The first
snapshot, scene and status of a new session replace the old ones; an `opened` event for a session that was already open (a
reset) is handled the same way, and the gamepads are sent again from inside it.

**A session that replaces one that was opened goes to a fresh worker.** `AppServices.openSession` (used by Open project,
Restart code and Apply and restart) drops the link, which closes its worker process, and opens another, with the session
asked for. The first session of a link is opened on it as it is. The reason is a measurement: a worker keeps about 20 MB of
every world it held (`docs/architecture/app.md`, "Why Restart code starts a new worker"), so a team restarting all day
would end with a gigabyte worker. A new worker costs a few seconds, during which the viewport says it is connecting.

## Threads

The link's listener calls arrive on its own dispatcher thread and reach the rest through thread-safe holders
(`ViewportFeed`, the `StateFlow`s); the preparer calls back on its own thread into `PrepareModel`; Compose and input run on
the AWT event queue, with file-system work on `Dispatchers.IO`; the viewport draws on `render-loop`, GLFW (the hidden
context window and joystick polling) has a thread of its own and the settings are written on `settings-writer`. The window
never blocks on any of them.

Lock order is `ViewportInput` then `GamepadRouter` then `HeldKeys`, and `HeldKeys` and `OrbitCamera` call out only
after releasing their own lock, or to something that never blocks, so the order cannot be inverted. `PracticeSession` gives
the link `addListener` and `open` under its own lock (they only queue, so a session asked for is always opened by a link
that is already listened to, and requests arrive in the order they were made); the other commands read the link under the
lock and call it outside it, and its listener methods store under the same lock.

**Viewport.** The render thread draws the scene into an offscreen framebuffer, reads it back and Compose shows it as a
bitmap like any other content, so menus, dialogs and snackbars overlap it (`viewport-measurements.md` says why this and not
a native canvas); the renderer is described in the Javadoc of `desktop-ui` and the packages `ui.render` and `ui.viewport`.
The loop interpolates one snapshot interval behind the newest and draws only when something changed, so a world at rest
costs no render time. A pacer may only be used by one thread, so a changed frame cap puts a new pacer in place that the
render thread takes before its next frame. The viewport is shown only while the Simulator screen is composed: when it
leaves, `ViewportEngine.setShown(false)` stops the drawing, releases the held keys and tells the controllers' gate, and when
it comes back `ShownFrame` (kept by the engine) already holds the last picture, so the screen is never blank in between.

## Input

- Keys are captured only while whatever takes keys (the viewport, or the key test area of the Controls screen) has focus,
  the window is the active one and no menu or dialog is open; the moment any of the three stops being true, held keys are
  released. Keys that the keyboard map uses are consumed, so the arrow keys do not move focus away from the robot; Tab,
  Escape, Enter, Space, Backspace, Delete and the modifier keys can never be bound.
- **Where a gamepad listens** is a setting per gamepad: Automatic (the default), Keyboard, None, or a named controller.
  `GamepadRouting.resolve` is the pure rule: named controllers are matched first, by the model's GUID (so one is found again
  whatever joystick slot it comes back in); then the keyboard; then Automatic gamepads take the first controller nobody has,
  and gamepad 1 falls back to the keyboard when none is left. A controller or the keyboard is never given to two gamepads:
  the second claim gets nothing and both cards say so. A named controller that is not connected leaves its gamepad neutral
  without calling it a problem. Both gamepads always exist and are neutral when nothing is assigned.
- **The robot is driven only while the Simulator screen is shown**, the window is active and no modal is open
  (`ViewportInput` gates both keys and controllers). The Controls screen has its own live preview (`GamepadRouter.preview`),
  which ignores that gate, so a controller can be tried while the robot is not listening.
- **Controls screen**: a card per gamepad with the source menu, live stick, trigger and button displays and sliders for the
  dead zones (`PadMapping`: round stick dead zone with the travel left past it stretched to full, linear trigger dead zone;
  defaults 8 % and 2 %), and a keyboard card with a test area and the table of bindings (press a key's button, then the new
  key; a key that is reserved or already used is refused with the reason). The default keys: W A S D left stick, arrows
  right stick, Q E bumpers, Z C triggers, I J K L Y X A B, T F G H D-pad, X V stick buttons, 1 2 3 Start Back Guide;
  diagonals stay on the unit circle.
- `GamepadRouter` passes a state to the session only when it changed; the session sends both again when a session (re)opens.

## Settings on disk

`%LOCALAPPDATA%\FTC-Sim\config\ui.properties` (`UserDirectories`, a few lines in this module). A UTF-8 properties file with
`format=1`: `display.*` (render scale, frame cap, overlay; an old `lengthInches` is ignored), `gamepad.N.*` (assignment kind,
controller GUID and name, dead zones), `keyboard.<CONTROL>=<AWT key code>` (0 is unbound), `project.N.*` (a recent project:
its folder, name and, when it was run, the field, alliance and start pose of that run, the OpMode that finds its hardware,
and `robotSkipped`). An older `recent.N.*` run list, the `trusted.N` keys and the `run.seed` and `run.randomSeed` keys of
earlier versions are ignored, not read. The file is read once when the application starts and written after every change, on
a thread of its own and coalesced (any number of changes before the writer runs is one write), through a temporary file that
then replaces the real one. A missing file gives the defaults. A value that makes no sense is replaced by its default and the
rest is kept; a file that cannot be read at all, or has another `format`, is moved to `ui.properties.corrupt` (nothing is
thrown away), the defaults are used and a snackbar says so. A write that fails says so too and the settings keep working in
memory. `--settings=<file>` puts them elsewhere, which scripted runs use.

## Tests without a window or a hand on the machine

No test uses the real mouse, keyboard, focus or window, and none may. `PracticeSmokeTest` runs the real application stack
headless: the services on a fake host, the real screens drawn into memory by Compose's `ImageComposeScene`, the real render
thread on a hidden OpenGL context, and it writes `build\screens\*.png` of every screen. The other screen tests drive Compose
with synthetic clicks, text, key presses and scrolls, with bounded waits on state and no sleeps; the pure parts (routing,
mapping, settings codec and store, navigator, prepare model, key rules) are unit tests with no Compose. Tests that need
OpenGL 3.3 are skipped, not failed, without it. `RealFieldViewportTest` in `acceptance-tests` draws the real fields with the
application's renderer (`viewport-measurements.md`). `docs/testing.md` owns the test strategy and the lists.

## Deliberately not done yet

The window's open points that matter to a driver are in `docs/roadmap.md` ("The window, importing and distribution"). Also not done:

- Walls translucent: DECODE's perimeter walls are polycarbonate in reality, but they are drawn opaque, so a low camera behind
  the near wall loses the pieces that lie against it. Drawing them translucent needs a sorted pass and a way to know which
  scenery is glass.
- Theming beyond the standard M3 schemes, and a collapsible or resizable details panel. The folder chooser is Swing's in the
  Windows look, because the native one cannot pick a folder.
- The router and the Controls screen are tested with synthetic readings: no controller was ever attached, so hot-plug and
  GUID matching on hardware are untested.
