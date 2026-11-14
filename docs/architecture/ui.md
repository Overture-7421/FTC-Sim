# Desktop UI

`desktop-ui` is the simulator's window: Material 3 chrome (Compose Multiplatform for Desktop) around a 3D viewport
(LWJGL, OpenGL 3.3). It draws what a `SimulatorLink` tells it and sends back what the driver does; it owns no simulation
state and depends only on `sim-contracts` and `platform`. Kotlin is used only for Compose and the state holders beside
it; the renderer, the input logic, the fake link, the fake program preparer and the host seam are Java.

```
.\gradlew.bat :desktop-ui:run                                  opens the app on the fake host (fake link, fake preparer)
.\gradlew.bat :desktop-ui:run --args="--no-focus --practice --exit-after=8 --settings=C:\temp\ui.properties"
                                                               scripted launch: no focus stolen, opens the default
                                                               practice run, closes itself, keeps its settings elsewhere
.\gradlew.bat :desktop-ui:check                                tests, about 420, nothing waits on real time to be right
```

## The host seam

The window is started by whoever has the real things to show: `dev.ftcsim.ui.App.run(AppHost host, String[] args)` (a
Java-callable static method; the `app` module's `main` calls it). `AppHost` has two parts of its own and one answer:

- `openLink()` returns the `SimulatorLink` and may start a worker process, so it can take seconds and can fail with an
  `IOException` whose message is for people. `AppServices` calls it **off the UI thread**, on a single thread named
  `link-opener`, and hands the result to the session. Until it is there, a session that is asked for waits for it
  (`Connecting to the simulator...` on Practice and Setup); when it cannot be had, Practice and Setup show the message
  with **Try again**, which opens a link again. The link is closed when the window closes (a link that arrives after the
  window was closed is closed on the spot).
- `preparer()` returns the `ProgramPreparer`: `inspect(folder)` says what a folder is (a team's project, an already
  prepared program, or neither, with the reason), `prepare(request, listener)` runs the project's own Gradle build and
  reports steps, output and the end. The Prepare screen below is its only user.
- `programRequired()` (added to the seam with a default of `true`) says whether a run needs a prepared program. The fake
  host answers `false` because it runs no robot program; the real host keeps the default.

`main` of `desktop-ui` builds a `FakeAppHost` and calls `run`, so `:desktop-ui:run` keeps working for interface work.
`FakeAppHost` has a `FakeSimulatorLink` that takes 0.6 s to open (so the window can be seen waiting) and a
`FakeProgramPreparer`.

`FakeProgramPreparer` recognises folders by a marker file (`manifest.json` is a prepared program, `settings.gradle` or
`.gradle.kts` a project, anything else is not recognised and says what is missing) and plays a preparation of six
steps with plausible Gradle output on a `Scheduler`, so a test moves time by hand (`ManualScheduler`) and the application
lets real time pass. The outcome follows the folder's name so every state can be seen: a name containing `broken` fails to
compile at step 2 with the error lines and a build log file, `no-sdk` finds no Android SDK at step 1, anything else
succeeds with a program, an SDK version, three OpModes and two warnings. A request that is not trusted is refused, a
cancel ends with `CANCELLED` a moment later.

## The shell and the screens

A Material 3 navigation rail on the left lists **Home, Prepare, Setup, Controls, Practice and Settings** with a Back
button above it. `Navigator` keeps the history as the places visited before, each at most once, so Back always leads
somewhere different from where you are and the history cannot grow without end. Ctrl+1 to Ctrl+6 go to the destinations in
rail order and Alt+Left goes back, from wherever the focus is; every rail item is a standard focusable item, so Tab and
Enter work. Only the shown destination is composed, so each screen's own state lives outside the composition (`AppUi`: the
Practice menus and tab, the Setup form, the Controls rebinding, the preparation) and the session, viewport and settings live
in `AppServices`; leaving Practice therefore does not stop the run, leaving Prepare does not stop a preparation, and coming
back shows each as it is.

- **Home**: a "New practice run" button, "Open project" and "Open practice package" (each a folder chooser) and the recent
  runs, newest first: field, alliance, profile, seed and program, with one-click Open and a remove button. An empty list says
  so. A run is the same run at any speed; opening an old one moves it to the top; ten are kept. A recent run names its field by
  the catalogue's display name, or by the field's id until the catalogue has arrived.
- **Choosing a folder** (Home and Prepare share one routine, `AppUi.openFolder`): the preparer's `inspect` runs off the UI
  thread; a **project** goes to Prepare with it chosen, a **prepared program** goes to Setup with the program filled in, and
  anything else stays where it was chosen with the reason in a dismissible card ("That folder cannot be used"). The card
  belongs to the screen it was raised on and is gone when the person goes elsewhere. Opening a project while another is
  being prepared is refused in words, and the preparation is left alone.
- **Prepare**: see below.
- **Setup**: built from the catalogue the link reports (`Listener.catalogue`): the **field** chooser (display names), the
  **alliance**, the **seed** (random, drawn when the run starts and kept with it, or fixed), the **robot profile** (a menu of
  the bundled names and "Choose a file..."), the **program** (a folder of a prepared package, with Browse; required only
  when the host says so), and the **start pose**: the chosen field's start poses (`FieldChoice.startPoses`), as many as the
  alliance may use, or "Custom position" with numbers. The port gives a start pose a name and numbers and nothing about
  alliances, so `StartPoses` goes by the names the games use: a pose whose name begins with RED or BLUE (any case) belongs
  to that alliance, any other name to both; a field whose poses name no alliance offers them all. Switching alliance moves a
  pose of the other alliance to the first one of the new; switching field takes the first pose of the new field in a form
  nobody has touched and keeps numbers the person typed. Before the catalogue arrives the screen says "Connecting to the
  simulator...", the parts that depend on it wait, and Start practice says why it is off ("Waiting for the simulator."). What
  is wrong with a field is said under it, with an icon as well as colour. `SetupRules` is the one place that says what a valid
  form is; the form is checked off the UI thread (the file system is asked about the paths there) and **Start practice**
  works only for the exact form and the exact catalogue that were checked and found right. Starting builds a
  `SessionRequest`, remembers the run, opens it on the session and goes to Practice. A profile without `/`, `\` or `:` is a
  bundled name, anything else must be an existing file; a program is an existing folder.
- **Controls**: see Input below.
- **Settings**: render resolution (50, 75 or 100 percent of the viewport's size; the smaller picture is drawn by the
  render thread and stretched with filtering), the frame cap (60, 90 or 144, never above the display: the page says what
  the display's refresh rate makes of it), the performance overlay (default off), and where the settings file is.
- **Practice**: below.

File and folder choosers sit behind `FileChooser` (`AwtFileChooser` is the real one: the native dialog for files, Swing's
folder chooser in the Windows look for folders, because the native one cannot choose a folder), and opening a build log
sits behind `LogOpener` (`AwtLogOpener` hands the file to `java.awt.Desktop` on an IO thread). Tests use `FakeFileChooser`
and `FakeLogOpener`: no dialog opens and no program starts; the two real ones are untested for that reason.

Icon-only buttons (Back, pause, reset, remove a recent run, clear a key) have a plain tooltip (`Tip`) and a content
description; custom controls carry descriptions that say what they are and what they hold ("Gamepad 1 listens to:
Automatic", "Left stick up: W. Press to change.", "Robot profile: hornet-2026").

## The Prepare screen

Turns a team's project into a program the simulator can run. `PrepareModel` (state flows, no Compose) holds the chosen
project, the preparation and its end; the screen only shows it, so nothing waits for the preparer on the UI thread and the
preparation outlives the screen. Its states:

- **Idle**: nothing chosen; a button opens the folder chooser. **Ready**: the project's name, folder and the preparer's
  one-sentence summary, with **Prepare**.
- **Consent.** Preparing runs the project's own Gradle build and so its code on this computer, so before the first
  `prepare` for a folder a Material 3 dialog says in plain words: "Preparing runs this project's own Gradle build and its
  code on this computer. Only continue with projects you trust." (Cancel / Prepare). Agreeing is remembered for the folder in
  the settings file (`trusted.N`, the folder's absolute path in lower case, the newest hundred), and `Request.trusted` is true
  only after that agreement; the model never calls `prepare` with `trusted = false`. Cancel changes nothing and is not
  remembered. A question left open when the screen is left is withdrawn.
- **Running**: a step list with the preparer's labels (done: a check mark, active: a spinner, waiting: an open circle; the
  label of a step is only known when it begins, so a waiting step reads "Step 4 of 6"), a **Cancel** button that is disabled
  from the moment it is pressed until the preparer says the preparation ended, with "Cancelling" beside it, and the output.
- **Output**: selectable monospace text in a list of its own (a lazy list, so thousands of lines cost nothing), the newest
  2000 lines kept (`OutputLog`; the note "Showing the newest 2000 lines of output; the N before them are not shown" says
  how many were dropped, and the build log file has all of it). It follows the newest line until the person scrolls up to
  read and follows again once they scroll back to the end (wheel and touch are seen by a nested scroll connection, which
  the screen's own scrolling to the end does not go through; Page Up, Page Down, Home and End work when the list has focus).
- **Failed**: the preparer's message in words, the details lines that show why (selectable), **Open build log** when the
  preparer gave a log (through `LogOpener`; if it cannot be opened the reason is shown there) and **Prepare again**. No
  advice of the window's own is added: the message is already written for students. A **cancellation** (`CANCELLED`) is
  shown neutrally as "Preparation cancelled", not as an error.
- **Finished**: the program's name and SDK version, its OpModes and its warnings as expandable lists (the warnings say
  that they point to code that may not work in the simulator and are not claims that it fails), the program folder, and
  **Use this program**, which fills the Setup form's program in and goes there; the run is remembered in the recent runs
  like any other when it is started.

Preparing and a running practice session coexist: nothing in the preparation touches the session.

## The Practice screen

- **Top bar**: OpMode drop-down, Init, Start and Stop (enabled the way the Driver Station enables them: Init from idle
  with an OpMode picked, Start after Init, Stop any time after Init, the OpMode cannot change while one is
  initialised or running; `Controls.of` is the one place that says so), then **Match**, then pause, speed (0.25x to 4x) and
  reset, which asks for confirmation.
- **Match.** Offered when the robot is idle and no match is armed or played. Its dialog asks for an optional autonomous
  OpMode ("None: the match starts with the driver-controlled period" is a choice) and the driver-controlled one, from the
  program's own OpMode kinds, with the OpMode picked in the top bar, or else the first of each kind, chosen already
  (`MatchPlan.suggest`); **Arm** sends `armMatch`. Once the match is armed or under way the top bar swaps the free-practice
  buttons for the match's own: the OpModes in a line, **Start match** (only while armed) and **Stop** (gives up an armed
  match, ends one under way); the free-practice buttons are not shown then rather than left dead. The Match button is off
  while it cannot work and says why in its tooltip and to screen readers ("Stop the OpMode first: a match starts from idle.",
  "A match has been played. Reset the session to play another.", "This program has no teleop OpMode, which a match needs.").
  The window has no clock of its own: the countdown and the period come from `SessionStatus.match` alone (the simulator times
  the periods in simulated time, so pausing or changing the speed moves the countdown with it, and the lengths are not
  known to the UI). A scoreboard over the viewport shows the state (Armed, Autonomous, Transition, TeleOp, Ended), the whole
  seconds left of the period with a progress bar, and for a match that ended the final score with a reminder that another
  match needs a reset; the status strip says the same in a line. Free practice is exactly as before while no match is armed.
- **Viewport**: the field, the robot and the pieces, drawn from the session's `FieldDescription` and `RobotInfo` and
  interpolated between snapshots. The robot is a box in the colour of its alliance with a yellow marker on its front edge;
  the field's AprilTags are drawn where the field puts them (on the field, or riding on a body such as the obelisk or a
  HIVE); the floor has the seams between its 24 inch tiles. Camera presets float over it. Top and Driver stand at the
  driver's end of the field: behind the robot along its heading when it starts square to the walls (BIOBUZZ, DECODE's
  audience side), and at the audience's end, looking along -X, when it starts in a corner facing across the diagonal
  (DECODE's goal side), because behind it is the goal. Chase follows the robot from behind and does not look through
  anything taller than half a metre (a goal, a HIVE): it stops in front of it, and where that would leave it nearer than
  1.1 m (a robot standing against a goal) it goes round to the nearest side that is clear and comes back when behind is
  clear again. Dragging leaves any preset for Free, and a wheel zooms. A focus ring shows when it has the keyboard, and a
  hint says what the keys do. The session's field's human actions are chips in a corner.
- **Side panel**: Telemetry (the Driver Station lines, with the SDK's global warning and error on top), Hardware,
  Score and Problems. Problems are named in words ("Error - Robot program", "Warning - Simulator"), never by colour
  alone; the tab carries a count badge, an error raises a snackbar that leads to the tab.
- **Status strip**: where the session is (no session, opening, idle, initialised, running, failed, or the match), and once
  it is open the simulated time and the speed kept against speed asked for (red when the machine cannot keep up), then the
  problem count and the viewport's frame rate, or "Viewport at rest".
- **Performance overlay** (Settings, off by default): a small card over the top end of the viewport with the frame
  interval (p50 and p99), the render cost on the CPU and the GPU (p50), and the rates of frames and snapshots, sampled twice
  a second from recorders that keep the newest few thousand frames and are only fed while the overlay is on.
- **Refusals and failures.** A command the link refuses (`Listener.refused`: an Init while a match is armed, a match while an
  OpMode runs, ...) is a snackbar with the message, and the session goes on exactly as it was; a refusal never ends it. Only
  `failed` does: while a session is open it ends the session ("The session could not go on" with **Try again**, which asks
  for the same session on a link that is opened anew, since a failed link cannot be asked again, and **Change the setup**).
  A link that cannot be had is said first ("The simulator could not be started", with the message and **Try again**).
- While the session opens the controls are disabled and an overlay says so. Nothing waits on the link on the UI thread, and
  the window closes at once while the link shuts down. A reset shows the session opening again, because the link answers a
  reset with `opened` for a new session with the same request and the speed set last (the match is gone with the old
  session); the OpMode the driver picked is kept if the new session still has it.

Every control is a standard M3 component, so Tab reaches it and Enter or Space operates it and it draws its own focus
state (a test presses Enter on a focused Init button); the viewport takes focus with a click or with Tab and shows a ring.

## Link, session and state

`PracticeSession` exists before any link does: the application opens the link off the UI thread and hands it over with
`attach` (a session asked for before then is opened on it at once; a link that is replaced is dropped with `detach` and
nothing it says afterwards is heard). It registers as the link's `Listener` once per link, asks for the session with
`open(request)` (a new request replaces the one that is open; the Home screen's reopen goes through the same call), and
turns what comes back into separate `StateFlow`s: `link` (connecting, connected or unavailable with the application's
message), `session` (none, opening, open or failed, the OpModes, the one picked), `run` (phase, paused, speed), `timing`
(simulated time at most ten times per second of simulated time, speed kept), `match` (the state and the whole seconds left,
so the 30 Hz status changes the screen once a second), `telemetry`, `hardware`, `score`, `problems` and `catalogue`, plus the
`refusals` event stream. Each part changes as rarely as its content, so the 30 Hz status wakes only the composables that show
something that changed; the contracts are declared stable to the Compose compiler (`compose-stability.conf`) so that equal
values skip recomposition. The first snapshot, scene and status of a new session replace the old ones; an `opened` event
for a session that was already open (a reset) is handled the same way, and the gamepads are sent again from inside it.

`FakeSimulatorLink` is an in-process link for developing and testing without a worker: a 12 ft field with walls, two
goals, a barrier, a turnstile and two kinds of piece, a kinematic chassis that gamepad 1 drives while an OpMode runs,
four canned OpModes (a teleop, a self-driving square, a telemetry spammer, one that crashes after three seconds),
telemetry, hardware readouts, score, problems and human actions. It tells its listeners its **catalogue** (three fields,
`none`, `decode` and `biobuzz`, with the start poses and human actions of each, and two profiles) from the link thread right
after a listener is added, as the real link does, and it plays a **timed match** with the same states as the simulator
(armed, autonomous, transition with the teleop OpMode initialised, teleop, ended; Stop gives up an armed match or ends one
under way; the periods are timed in simulated time, 30 s, 8 s and 120 s by default, and a test makes them short). A
command it cannot do (a match while an OpMode runs, an Init while a match is armed, no session open) comes back through
`refused`, never `failed`, in words of its own (the real simulator's differ). A thread of its own steps it at 240 Hz of simulated time
against the wall clock at the chosen speed, calls the listeners one at a time, and rests (no more than 30 passes a second,
woken by any command) while nothing runs or moves. `FakeWorld` is pure and deterministic; the link only paces it.

## Threads

| Thread | Does | Hands over through |
|---|---|---|
| link (`fake-simulator-link`; the remote link's dispatcher) | listener calls: `snapshot`, `status`, `opened`, `failed`, `refused`, `catalogue` | `ViewportFeed` (lock-free), the `StateFlow`s and the refusal stream |
| link opener (`link-opener`) | `AppHost.openLink()`, one request at a time; closing a failed link | `PracticeSession.attach` and its `link` flow |
| preparer (its own; `fake-program-preparer` for the fake) | `ProgramPreparer.Listener` calls: steps, output, the end | `PrepareModel`'s flows and `OutputLog`, which are thread-safe |
| UI (AWT event queue, Compose) | composition, drawing the bitmap, key and pointer events, link commands (they only queue), `inspect` is launched from it but runs on IO | `HeldKeys`, `OrbitCamera`, `GamepadRouter` |
| IO (`Dispatchers.IO`) | `ProgramPreparer.inspect`, form checks against the file system, opening a log file | the result back to the UI thread |
| render (`render-loop`) | interpolates, draws offscreen, reads back, copies into Skia memory | newest frame in an `AtomicReference`, a Compose state tick |
| GLFW (`glfw`) | owns GLFW: the hidden context window, joystick polling at about 125 Hz | `GamepadRouter.padsPolled` |
| settings writer (`settings-writer`) | writes the settings file after a change, one write for any number of changes | none |
| startup, shutdown | GLFW and viewport start-up; closing the link and flushing the settings | none; they end |

Lock order is `ViewportInput` then `GamepadRouter` then `HeldKeys`, and `HeldKeys` and `OrbitCamera` call out only
after releasing their own lock, or to something that never blocks, so the order cannot be inverted. `PracticeSession` gives
the link `addListener` and `open` under its own lock (they only queue, so a session asked for is always opened by a link
that is already listened to, and requests arrive in the order they were made); the other commands read the link under the
lock and call it outside it, and its listener methods store under the same lock.

**Viewport.** `SceneModel` (plain data) is built once per session from the field and robot descriptions: scenery in
field coordinates (a box as wide as the field whose top is in the floor plane is the floor and is drawn with tile seams
over its own colour; the renderer adds no floor of its own under one that covers the scene, which removed a full-field
second shading pass), the AprilTags as three thin layers each (black square, white square, black core, half a millimetre
apart), and for each body kind the solids that make it up; tags that ride on a body are looked up by its entity id. The
render thread draws it with instanced boxes, cylinders and spheres (five draw calls for the whole DECODE field), a lit
floor, one light, matte boxes and glossy spheres, and one translucent planar shadow under everything that moves (a stencil
lets overlapping shadows darken a pixel once), into a multisampled offscreen framebuffer, reads it back
through two pixel buffers (the read of frame N completes while N+1 renders) and copies it once into Skia memory;
Compose draws that as a bitmap like any other content, so menus, dialogs and snackbars overlap it (see
`viewport-measurements.md` for why this and not a native canvas). The loop interpolates one snapshot interval behind the
newest, whatever rate snapshots arrive at, shows a restart or a long pause as it is instead of as slow motion, and
draws a frame only when the interpolated poses, the camera, the scene or the surface changed, plus two to flush the
readback; then it sleeps until woken. A world at rest therefore costs no render time however often it republishes. Pacing is
`min(display refresh, cap)` (an integer divisor of a faster display when one is close to the cap) on the Windows
high-resolution timer; the cap is a setting, and a changed cap puts a new pacer in place that the render thread takes
before its next frame and closes the old one itself, because a pacer may only be used by one thread.

The viewport is shown only while the Practice screen is composed. When it leaves, `ViewportEngine.setShown(false)` makes
the render loop stop drawing (it rests, 10 wake-ups a second), releases the held keys and tells the controllers' gate;
when it comes back the loop draws a frame at once, and `ShownFrame` (kept by the engine, not by the composition) already
holds the last picture, so the screen is never blank in between. The render scale changes the size of the offscreen target
(the surface's size times the percentage, in device pixels) and the picture is stretched with linear filtering when it is
smaller than the area.

## Input

- Keys are captured only while whatever takes keys (the viewport, or the key test area of the Controls screen) has focus,
  the window is the active one and no menu or dialog is open (the arm-a-match dialog included); the moment any of the three
  stops being true, held keys are released. Keys that the keyboard map uses are consumed while one of those has focus, so the
  arrow keys do not move focus away from the robot; Tab, Escape, Enter, Space, Backspace, Delete and the modifier keys can
  never be bound.
- **Where a gamepad listens** is a setting per gamepad: Automatic (the default), Keyboard, None, or a named controller.
  `GamepadRouting.resolve` is the pure rule: controllers named in the settings are matched first, by the model's GUID
  (so a controller is found again whatever joystick slot it comes back in, and two identical ones serve gamepad 1 and 2 in
  slot order); then the keyboard for a gamepad set to it; then Automatic gamepads take the first controller nobody has,
  and gamepad 1 falls back to the keyboard when none is left and nothing else uses it. A controller or the keyboard is never
  given to two gamepads: the second claim gets nothing and both cards say so ("Gamepads 1 and 2 are both set to the same
  controller, but only one is connected", "The keyboard can drive only one gamepad at a time"). A named controller that is
  not connected leaves its gamepad neutral and says so without calling it a problem. Both gamepads always exist and are
  neutral when nothing is assigned; a controller that disconnects frees its place and its gamepad is neutral at once.
- **The robot is driven only while the Practice screen is shown**, the window is active and no modal is open
  (`ViewportInput` gates both keys and controllers; the controllers do not need the viewport's own focus). The Controls
  screen has its own live preview (`GamepadRouter.preview`), which ignores that gate, so a controller can be tried while the
  robot is not listening.
- **Controls screen**: a card per gamepad with the source menu (controllers listed by name; a connected one without a
  gamepad mapping is listed and disabled), what it listens to, both sticks as circles (the grey disc is the dead zone, the
  small dot what the controller reports, the large one what the robot gets), the triggers, fifteen button lights, and
  sliders for the stick and trigger dead zones (up to 50 %; the stick's is round and the travel left past it is stretched
  to full, as `PadMapping` does). The list of controllers is polled at 30 Hz while the screen is shown, so a controller
  plugged in or removed appears and disappears by itself. The keyboard card has a test area (focus it and press keys; the
  gamepad assigned to the keyboard moves above) and the table of 25 bindings by group: press a key's button, then the new
  key; Escape cancels; a key that is reserved or already used is refused with the reason and the control keeps waiting;
  each binding can be cleared, and the defaults restored. A key shared by two controls (possible only through a
  hand-edited file) is flagged with both names. The rebinding captures the key inside the application window through
  Compose's key events, and the tests send synthetic ones.
- `GamepadRouter` passes a state to the session only when it changed; the session remembers both and sends them again when
  a session (re)opens. The mapping from GLFW's layout is `PadMapping` (round stick dead zone with rescaling, linear trigger
  dead zone; defaults 8 % and 2 %).

## Settings on disk

`%LOCALAPPDATA%\FTC-Sim\config\ui.properties` (the launcher keeps its caches in `%LOCALAPPDATA%\FTC-Sim` too; there is no
Java helper for it there, so `UserDirectories` is a few lines in this module). A UTF-8 properties file with `format=1`:
`display.*` (render scale, frame cap, overlay), `gamepad.N.*` (assignment kind, controller GUID and name, dead zones),
`keyboard.<CONTROL>=<AWT key code>` (0 is unbound), `recent.N.*` (profile, program, field, alliance, seed, start pose) and
`trusted.N` (the project folders the person agreed to have prepared, absolute, lower case, newest last, at most a hundred).
It is read once when the application starts and written after every change, on a thread of its own and coalesced
(any number of changes before the writer runs is one write), through a temporary file that then replaces the real one. A
missing file gives the defaults. A value that makes no sense is replaced by its default and the rest is kept; a file that
cannot be read at all, or has another `format`, is moved to `ui.properties.corrupt` (nothing is thrown away), the defaults
are used and a snackbar says so. A write that fails says so too and the settings keep working in memory. `--settings=<file>`
puts them elsewhere, which scripted runs use.

The default keys: W A S D left stick, arrows right stick, Q E bumpers, Z C triggers, I J K L Y X A B, T F G H D-pad, X V
stick buttons, 1 2 3 Start Back Guide; diagonals stay on the unit circle.

## Tests without a window or a hand on the machine

`PracticeSmokeTest` runs the real application stack headless: the services on a fake host, the real screens drawn into
memory by Compose's `ImageComposeScene`, the real render thread on a hidden OpenGL context. One test walks the whole
flow: Home, a project folder opened (Prepare, the consent question, the fake preparation running and finishing), "Use this
program" into Setup, a run started, and on Practice a match armed, started and stopped; it writes a screenshot of every
step. Others start a run from the Setup screen's form, click the viewport with a synthetic pointer event, hold W with a
synthetic key event, check the robot drove and that opening a dialog released the key, that leaving Practice releases the
keys, stops drawing and keeps the run going, that a half-size picture still shows the field, that the performance overlay
measures the real render thread, and check pixels (the robot in its alliance's colour, green and purple pieces, many shades). They write
`build\screens\*.png` (every screen, the Setup and Controls pages at full length). Without an OpenGL 3.3 context the
OpenGL-dependent tests are skipped, not failed.

`AppFlowTest` drives the whole interface on the fake host with synthetic clicks (no OpenGL): Home to Setup to Practice,
Init, away to Controls and back with the run intact, a recent run reopening with one click, "Open practice package" and
"Open project" routing by what the folder is, a folder that is neither explained in place and dismissed, a project refused
while another is being prepared, a preparation going on while another screen is shown and a practice session runs, and
settings reaching the engine and the key map. `PrepareScreenTest` shows every state of the Prepare screen (idle, ready, the
consent dialog, running, cancelling, cancelled, failed with and without a build log, finished with its expandable lists)
on the fake preparer played by a `ManualScheduler`, or on a scripted one where exact events matter, and checks that the
output follows the newest line and stops following when scrolled up. `AppServicesTest` covers the host: the link opening off
the UI thread, a link that cannot be had and the retry, a session asked for early, a failed session on a fresh link, and the
link being closed with the window, all with bounded waits on state and no sleeps. `PracticeScreenTest` covers the match
controls in every state and refusals; `SetupScreenTest` the catalogue-driven form (before it arrives, filled, a field or
alliance change changing the start poses). Each other screen has a Compose UI test with synthetic events (clicks, text,
key presses, hover for the tooltip, semantic slider actions, mouse-wheel scrolling), including Tab order on Home and the
rebinding of a key; the pure parts (routing, mapping, settings codec and store, form validation and model, navigator,
the prepare model and output log, match display, key rules) are unit tests with no Compose.

`FieldRendererTest` looks at the renderer's own pixels on a small offscreen target (skipped without OpenGL): the field's floor
keeps its colour and shows seams and no glare, a shadow dims the floor by a third and two shadows on one spot are one
shadow, a tag is a black border, a white square and a black core, the picture comes back the right way up, and a frame
reports what it asked the GPU to draw. `OrbitCameraTest` and `SceneModelTest` hold the camera and scene rules without
OpenGL (which way the driver looks, the chase camera stopping short of a goal or going round to its side, the floor and tag
detection).

`RealFieldViewportTest` (in `acceptance-tests`, because it needs a real `SimulationSession`) is the harness for how the
real fields look: it opens Hornet's profile on DECODE and BIOBUZZ, draws their description and snapshots with the
application's own renderer and camera presets on a hidden OpenGL context, writes 1280x720 pictures to
`acceptance-tests\build\screens\real-*.png` (top, driver, corners, chase and a view from in front of the robot for each field
and alliance, the obelisk and the goal tags, the ramp, pieces carried in the robot and in flight, a BIOBUZZ HIVE after it
tipped), checks in the pixels that blue is on the blue side with the right pieces and the robot's marker, and measures the
frame cost into `frame-cost.txt` (counts only are asserted). It is skipped, not failed, without OpenGL 3.3. The pictures are
a build output and are not committed; `viewport-measurements.md` describes what they showed.

## Deliberately not done yet

- The perimeter walls are drawn opaque. DECODE's are polycarbonate in reality, so a low camera behind the near wall loses the
  pieces that lie against it; drawing walls translucent needs a sorted pass and a way to know which scenery is glass.
- The robot is the chassis box from `RobotInfo` and nothing else: no wheels, and nothing for a mechanism that rises above
  the box (a piece carried up Hornet's conveyor is drawn above the box). A profile with visual parts would fix this.
- `FieldDescription` does not say which way the driver stands, so Top and Driver use the start pose's heading when it is
  square to the walls and the audience's end (-X) otherwise; a field that says so would remove the guess.
- The real file chooser (`AwtFileChooser`) and log opener (`AwtLogOpener`), and the real preparer and link, are only
  exercised by the application around the window; here they are behind interfaces and replaced by fakes.
- The Prepare screen cannot show the labels of steps that have not begun, because `ProgramPreparer.Step` is only reported
  when a step starts (a waiting step reads "Step 4 of 6").
- A timed match has no start countdown, no buzzer and no sound; the field's own scoring decides the final score.
- Theming beyond the standard M3 schemes, a collapsible or resizable side panel, a Windows-style folder chooser (Swing's
  chooser in the Windows look is used; the native one cannot pick folders).
- Rumble and LED feedback to controllers; controller hot-plug and GUID matching on hardware are untested (no controller
  was attached): the router and screens are tested with synthetic readings.
- Any measurement on a display faster than 60 Hz or on laptop-class hardware beyond what `viewport-measurements.md` holds,
  and a benchmark task; the performance overlay is for a person to look at.
