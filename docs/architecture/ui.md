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
.\gradlew.bat :desktop-ui:check                                tests, about 500, nothing waits on real time to be right
```

## The host seam

The window is started by whoever has the real things to show: `dev.ftcsim.ui.App.run(AppHost host, String[] args)` (a
Java-callable static method; the `app` module's `main` calls it; `App.run(host, options, started)` is the same with
parsed options and a hook that runs once the services exist). `AppHost` has three parts of its own:

- `openLink()` returns the `SimulatorLink` and may start a worker process, so it can take seconds and can fail with an
  `IOException` whose message is for people. `AppServices` calls it **off the UI thread**, on a single thread named
  `link-opener`, and hands the result to the session. Until it is there, a session that is asked for waits for it
  ("Connecting" on the Simulator); when it cannot be had, the Simulator and Projects show the message
  with **Try again**, which opens a link again. The link is closed when the window closes (a link that arrives after the
  window was closed is closed on the spot).
- `preparer()` returns the `ProgramPreparer`: `inspect(folder)` says what a folder is (a team's project, an already
  built program, or neither, with the reason), `prepare(request, listener)` runs the project's own Gradle build and
  reports steps, output and the end. The project runner (below) and the Projects screen are its users.
- `mappings()` returns the `ProjectMappings`: `find(project)` looks for the project's `ftcsim.json` and returns the
  whole mapping with the `run` defaults and whether the file is FTC-Sim's own copy (`keptByApp`); a file this version does not
  read is found too, with its `problem` and no mapping. `save(project, RobotMapping)` writes the whole mapping (copying a file it
  cannot read to `ftcsim.old.json` first) and `saveRunDefaults(project, RunDefaults)` rewrites the `run` block. All of them touch
  the disk, so they are called off the UI thread.
- `isFake()` says that nothing real runs; the window then says FAKE HOST in its title and on a banner on every screen.
  Only the fake host answers true, so the banner is reachable only through `:desktop-ui:runFake` and the tests.

`dev.ftcsim.ui.FakeApp` (test fixtures) builds a `FakeAppHost` and calls `App.run`, which is what `:desktop-ui:runFake`
starts, for interface work (the VS Code task is called `ftc-sim: run UI (fake host)`); `--practice` is its own option and
also opens the default fake session. The fixtures are `FakeAppHost`, `FakeSimulatorLink`, `FakeWorld`, `FakeField`,
`FakeProgramPreparer`, `FakeProjectMappings` and their `Scheduler` and `MatchTimes`; the tests of `desktop-ui` use them
through `testFixtures`, and no other module depends on them (a dependency from another module would have to be allowed
in `moduleRules`). The shipped window, `app` and the worker contain none of it. `FakeAppHost` has a `FakeSimulatorLink` that takes 0.6 s to open
(so the window can be seen waiting), a `FakeProgramPreparer` and a `FakeProjectMappings`, which keeps mappings in memory.
It is why the window can show four OpModes and a field that looks like nothing in the manual: the fake link has no robot
program and no real field. The real field and the real OpModes come only from the `app` module's host.

`FakeProgramPreparer` recognises folders by a marker file (`manifest.json` is a built program, `settings.gradle` or
`.gradle.kts` a project, anything else is not recognised and says what is missing) and plays a build of six
steps with plausible Gradle output on a `Scheduler`, so a test moves time by hand (`ManualScheduler`) and the application
lets real time pass. The outcome follows the folder's name so every state can be seen: a name containing `broken` fails to
compile at step 2 with the error lines and a build log file, `no-sdk` finds no Android SDK at step 1, anything else
succeeds with a program, an SDK version, three OpModes and two warnings. A cancel ends with `CANCELLED` a moment later.

## The shell and the screens

The window is **project-first**: a person opens the folder of their team's robot code and everything else follows from
it. There is no "practice run" and no "practice package" to know about; a built program is an implementation detail that
the application keeps for itself.

A Material 3 navigation rail on the left lists **Projects, Robot, Controls, Simulator and Settings** with a Back
button above it (in the code the Simulator is `Destination.PRACTICE`). `Navigator` keeps the history as the places visited
before, each at most once, so Back always leads somewhere different from where you are and the history cannot grow without
end. Ctrl+1 to Ctrl+5 go to the destinations in rail order and Alt+Left goes back, from wherever the focus is; every rail
item is a standard focusable item, so Tab and Enter work. Only the shown destination is composed, so each screen's own state
lives outside the composition (`AppUi`: the Simulator menus and tab, the settings form, the Controls rebinding, the build)
and the session, viewport and settings live in `AppServices`; leaving Simulator therefore does not stop the run, leaving
Projects does not stop a build, and coming back shows each as it is.

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

### The screens

- **Projects**: one **Open project** button (a folder chooser) and the **recent projects**, newest first: the project's name
  and folder and what it was last run with (field and alliance), with one-click **Open** and a remove button. Ten are
  kept. The folder to choose is the one with `settings.gradle`; any other folder says so in a short error that can be
  dismissed. Choosing the folder is the consent to build it: there is no trust dialog (`Request.trusted` stays in the
  contract and is always true from here). The build view, the question for the game and a failure also live on this screen
  (below).
- **Opening a project** (`AppUi.openFolder`, used by Projects and at start): the preparer's `inspect` runs off the UI
  thread; a **project** is opened by the `ProjectRunner`, a **built program** is refused with the name of the kind of
  folder to open instead, and anything else is refused with the reason. Opening a project while another is being built is
  refused, and the build is left alone.
- **At start** the application opens the project that was open last time, exactly as Open project does (so a changed
  source is built again and an unchanged one is not), unless `--no-last-project` is given, and shows the Simulator when it
  is done. If the folder is gone the screen says so; if the project is no longer set up for the simulator (no usable
  `ftcsim.json`, and the Robot page not skipped) it stays on Projects with a message instead of going on to the Robot page,
  which only a project the person opens now leads to. With no last project the window stays on Projects and nothing is opened.
- **Robot**: see "Robot" below.
- **Controls**: see Input below.
- **Settings**: render resolution (50, 75 or 100 percent of the viewport's size; the smaller picture is drawn by the
  render thread and stretched with filtering), the frame cap (60, 90 or 144, never above the display: "Display 144 Hz,
  drawing at 144 fps"), the performance overlay (default off), and where the settings file is.
- **Simulator**: below.

### The flow of a project

`ProjectRunner` is the one place that moves a person from a folder to a moving robot. Its `ProjectActivity` is `Idle`,
`Building`, `NeedsGame(project, program, stored)` or `Failed(project, message)`; `RobotState` is `FromFile` or
`Undescribed` (with the problem of a file that is there and cannot be read).
What the person should read goes out on the runner's `messages` flow (a snackbar) and what stays wrong goes to its
`problems` flow (the Problems tab).

1. **Build.** `BuildView` shows the project's name, "Step N of M: label" in the preparer's words, **Cancel** and a closed
   **Show log**. `PrepareModel` (state flows, no Compose; `PrepareModel(preparer)`) holds the build and its end, so
   nothing waits for the preparer on the UI thread and the build outlives the screen. The log is selectable monospace text
   in a lazy list; the newest 2000 lines are kept (`OutputLog`, which says how many were dropped) and the build log file
   has all of it. A good build goes straight on; a failure is `BuildFailedView`: one line, **Show details** and **Build
   again**. A cancellation is shown neutrally, not as an error.
2. **Game.** A project that has no remembered run on this computer and no `run.field` in its `ftcsim.json` is asked once
   for its game (`GameChoice`: BIOBUZZ or DECODE, the newest preselected, **Continue**). `continueWithGame()` carries on.
   Every other project starts with what it was last run with, else what the `run` block says.
3. **Start.** The session starts and the Simulator is shown (when the person is still on Projects or at the start; someone
   who went to Controls meanwhile is left there and finds it running). The runner reads the project's `ftcsim.json` off the
   UI thread and puts its `RobotMapping` in the `SessionRequest`; a project **without** a usable `ftcsim.json` runs with
   `RobotMapping.empty(projectName)`, a robot with no motors mapped (the simulator assumes no names, so every motor the code asks
   for is unmapped, the wheels too), and a problem says so ("No ftcsim.json: no motors are mapped"). A file that is there and
   is not a schema 2 file (an older version, or one that does not parse) is never read as a mapping and never overwritten
   silently: the problem is "ftcsim.json is from an older version" (or what could not be read), the project is treated as
   having no mapping, and saving first copies the file to `ftcsim.old.json`. The first time, the Robot screen is shown after
   the session opens so that the robot can be mapped. `skipRobot()` remembers the choice
   (`RecentProject.robotSkipped`, stored as `project.N.robotSkipped`) and the page does not open by itself again.
4. **Robot file.** `saveRobot(mapping)` writes the whole mapping through `mappings.save(folder, mapping)`, announces "Saved
   ftcsim.json", shows the Simulator and restarts the code, which reads the file again. `saveDefaults` writes the Setup tab's
   field, alliance and start position into the `run` block and announces it.

A build and a running session coexist: nothing in the build touches the session. The two ways a waiting project can be
resumed (the game chosen, the robot settled) start one session however they race.

### Robot

The **Robot** screen (`RobotScreen`) is the mapping page (`MappingScreen`, `MappingModel`, package `dev.ftcsim.ui.robot`) and
nothing else. The same page maps the robot of a project that has no `ftcsim.json` and edits one that has. A project that is
not open shows "No project open" and a button, and one that is building shows a spinner. The robot itself is fixed by the game
(`robots/fixed-robots.md`); the page only says which motor of the team's code is which part of it.

- The person picks an OpMode (none is preselected) and the app runs it up to Init to read the names it asks the hub for
  (`HardwareScan`, below). The page has two columns, each scrolling on its own. On the left, **Detected**: every motor the code
  asked for that is not mapped, a line each (only motors: servos, sensors and pins are not mapped, the simulator provides them).
  On the right, the fixed parts of the project's game, one row each: **Wheels** (the four), then **Mechanisms**: Intake roller,
  Conveyor, Shooter flywheel and, only when the project's field is `biobuzz`, Turret. Every mapped motor has its **model** (the
  catalogue of `Catalogue.motors()`) and a **Mirrored** switch. The four wheels share the drive motor, so changing the model on
  one changes it on all; another part shows the model it usually has (`RobotMapping.usualModel`) until another is chosen. A motor
  is **dragged** or **clicked and assigned** from the left to a row (`DragState`), and taken off with the cross on its chip. A
  wheel holds one motor, any other part holds any number, and a motor is on one part only. Hub and port are not asked: the
  design gives each motor the next free port.
- Below the rows: **Robot** (Name, Mass (kg), Wheel diameter (mm) and, for BIOBUZZ, Turret ratio: motor revolutions for one turn
  of the turret), **Hub** (two dropdowns, Logo faces and USB faces, which must be at right angles) and **Limelight**, whose
  **Settings** button opens a window with the six numbers of its mount (X, Y, Z in metres, Yaw, Pitch, Roll in degrees). The
  button's text turns red while one of them is not a number. What is wrong with a field is said under it in the contract's own
  words (`RobotMapping.problems`, kept as `MappingModel.problems()`), and `blockedReason` says in a few words why the button
  cannot be pressed ("Map all four wheels or none", "At most 8 motors fit on two hubs").
- With a file, `MappingModel.load` puts what the file says in the same rows and form (read off the UI thread after every
  build), so everything is edited the way a new robot is mapped. `edited` compares the page with what was loaded, whatever order
  the motors are in. A motor mapped to the turret in a game without one goes back to the list.
- The button at the bottom reads **Create** for a project without a usable file (with **Skip**, remembered for the project;
  enabled once a motor is mapped) and **Save** for one with a file (enabled when something differs from the file). Both call
  `ProjectRunner.saveRobot`, which writes the whole mapping, announces "Saved ftcsim.json", shows the Simulator and restarts
  the code.

The names the program asked for and the mapping does not place come from `SessionStatus.unmapped` (wire v5,
`architecture/link.md`); the window uses only the motors among them.

### Restart code

The robot code is the project's source on disk, and nothing watches it. A person edits, then presses **Restart code** in the
Simulator's top bar (the button is off while a build is under way). `ProjectRunner.restart()` builds what is on disk now
with the same preparer as Open project and, when the build ends, starts the session again with the new program and the
project's current `ftcsim.json`, keeping the field, alliance, start pose, seed and speed that were in use. It does not ask
and does not leave the Simulator screen. While it builds, `RestartStrip` is a thin strip under the top bar ("Step 2 of 6:
...") with **Cancel**. A failure is a snackbar "Build failed" and an entry in the Problems tab (with the build log's path,
which opens through `LogOpener`); the session that was running stays as it was. A source that did not change is not built
again, so a restart with no edits takes a moment. A source that changes while the build runs is built once more.

The runner keeps the last two programs of a project and deletes older ones (`docs/architecture/app.md`), so restarting
all day does not fill the disk.

File and folder choosers sit behind `FileChooser` (`AwtFileChooser` is the real one: the native dialog for files, Swing's
folder chooser in the Windows look for folders, because the native one cannot choose a folder), and opening a build log
sits behind `LogOpener` (`AwtLogOpener` hands the file to `java.awt.Desktop` on an IO thread). Tests use `FakeFileChooser`
and `FakeLogOpener`: no dialog opens and no program starts; the two real ones are untested for that reason.

Icon-only buttons (Back, the keyboard hint, remove a recent project, clear a key) have a plain tooltip (`Tip`) and a content
description; custom controls carry descriptions that say what they are and what they hold ("Gamepad 1 listens to:
Automatic", "Left stick up: W. Press to change.", "Open FTC-Hornet").

## The Simulator screen

- **Top bar**: an OpMode menu (the OpMode the person chose last, or the autonomous of the last match they armed, is kept
  with the project in the user's settings as `project.N.opMode`, and every session that opens for the project picks it
  again if the program still has it: `OpModeMemory`; the OpMode a session picks by itself is never kept) and one main button that goes **Init**, then **Start**, then **Stop** (enabled the way the
  Driver Station enables them: Init from idle with an OpMode picked, Start after Init, Stop any time after Init; the
  OpMode cannot change while one is initialised or running; `Controls.of` is the one place that says so; during a match
  the button reads Start match and then Stop), a **start position** menu (`StartPositionMenu`: every pose of the field, the
  chosen alliance's first, then the team's **named positions** for the field (`startPositions` in `ftcsim.json`, so shared
  with teammates), and **Edit positions...**, a dialog with a row for each (name, X, Y, heading, **Start** and remove), **Add
  position** (it begins where the robot starts now) and **Save**, which writes the whole list into the project; the entry is
  off for a project with no usable file; choosing a position starts the session again from it, as Apply on the Setup tab
  does; a field with no poses offers only the team's positions), a visible
  **Reset world** button (stops the code and puts the robot and the field back at the start in the same session, at once and
  with no question: `PracticeSession.reset`) with an arrow beside it (`RestartMenu`) that offers **Restart code** (above: build
  the project again) and a **more** menu (`MoreMenu`) with Match, pause and speed (0.25x to 4x).
- Over the viewport: the viewport's own notices
  ("No project open", "Connecting", "Opening", "Session stopped") and the robot-file notices.
- **Finding the hardware** (`HardwareScan`): driven by the Robot page, not by a question. `scan(opMode)` sends Init for
  that OpMode through `PracticeSession.init(name)` (the top bar's choice is untouched), waits for the phase to be Init, sends
  Stop and waits for Idle, then counts `SessionStatus.unmapped`; it follows `PracticeSession.openings`, a counter, because
  two sessions with equal descriptions are equal `SessionState`s and a flow would not tell them apart. A scan that does not
  reach Init within 10 simulated seconds says so with the program's first error; Cancel gives up one. The OpMode is
  remembered in `project.N.scanOpMode` and scanned again by itself when a new session opens, so the Robot page is current.
- **Setup tab** (`StartPanel`, handed to the screen by the application so that the screen knows nothing of projects): the
  **Field**, the **Alliance** (Red or Blue) and the **start position**: the chosen field's poses for that alliance, or
  "Custom position" with X, Y and a heading in degrees. `StartPoses` goes by the names the games use: a pose whose name
  begins with RED or BLUE (any case) belongs to that alliance, any other name to both; a field whose poses name no
  alliance offers them all. Switching alliance moves a pose of the other alliance to the first one of the new; switching
  field takes the first pose of the new field in a form nobody has touched and keeps numbers the person typed. A mistake in
  the numbers is a short phrase next to the field. `SetupRules` is the one place that says what a valid form is, and the
  catalogue from the simulator is what it offers.
  - **Apply** starts the session again with these, with the same program and (when the seed is random) the same seed, so
    that the alliance or the pose is the only difference between two runs; it does not build. It restarts on a fresh
    worker (`docs/architecture/app.md`), which takes a few seconds. A changed field draws a new seed, because it is another
    run. The seed has no control: every run draws one when it starts.
  - **Save as default** writes the field, alliance and start pose into the `run` block of the project's `ftcsim.json`, so
    that teammates start the same way; it changes nothing else in the file, and is off for a project with no file.

- **Match.** Offered when the robot is idle and no match is armed or played. Its dialog asks for an optional autonomous
  OpMode ("None: the match starts with the driver-controlled period" is a choice) and the driver-controlled one, from the
  program's own OpMode kinds, with the OpMode picked in the top bar, or else the first of each kind, chosen already
  (`MatchPlan.suggest`); **Arm** sends `armMatch`. Once the match is armed or under way the top bar swaps the free-practice
  buttons for the match's own: the OpModes in a line, **Start match** (only while armed) and **Stop** (gives up an armed
  match, ends one under way); the free-practice buttons are not shown then rather than left dead. The Match button is off
  while it cannot work and says why in its tooltip and to screen readers ("Stop the OpMode first: a match starts from idle.",
  "A match has been played. Reset the world to play another.", "This program has no teleop OpMode, which a match needs.").
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
  clear again. Dragging leaves any preset for Free, and a wheel zooms. A focus ring shows when it has the keyboard. The session's field's human actions are chips in a corner.
- **Details panel**: hidden until the **Details** button at the viewport's bottom right is pressed; it then floats over the
  viewport above the button (it takes no keys from the robot, and the button closes it). Four tabs. **Code** has the Driver Station telemetry lines (with the SDK's global warning and error on
  top), the hardware readouts and the console. **Score** has the field's score. **Problems** is the one place that lists
  what went wrong, in words ("Error - Robot program", "Warning - Simulator"), never by colour alone; the tab carries a
  count badge and an error raises a snackbar with **Show**, which opens the panel on the tab, as the problem count in the
  status strip does. **Setup** is above.
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
  the window closes at once while the link shuts down. A reset does not show the session opening: the worker resets the world in place (below) and answers with
  `opened`, so the window starts the world again (the match is gone); the OpMode the driver picked is kept if the session still has it.

Every control is a standard M3 component, so Tab reaches it and Enter or Space operates it and it draws its own focus
state (a test presses Enter on a focused Init button); the viewport takes focus with a click or with Tab and shows a ring.

## Link, session and state

`PracticeSession` exists before any link does: the application opens the link off the UI thread and hands it over with
`attach` (a session asked for before then is opened on it at once; a link that is replaced is dropped with `detach` and
nothing it says afterwards is heard). It registers as the link's `Listener` once per link, asks for the session with
`open(request)` (a new request replaces the one that is open), and
turns what comes back into separate `StateFlow`s: `link` (connecting, connected or unavailable with the application's
message), `session` (none, opening, open or failed, the OpModes, the one picked), `run` (phase, paused, speed), `timing`
(simulated time at most ten times per second of simulated time, speed kept), `match` (the state and the whole seconds left,
so the 30 Hz status changes the screen once a second), `telemetry`, `hardware`, `score`, `problems` and `catalogue`, plus the
`refusals` event stream. Each part changes as rarely as its content, so the 30 Hz status wakes only the composables that show
something that changed; the contracts are declared stable to the Compose compiler (`compose-stability.conf`) so that equal
values skip recomposition. The first snapshot, scene and status of a new session replace the old ones; an `opened` event
for a session that was already open (a reset) is handled the same way, and the gamepads are sent again from inside it.

**A session that replaces one that was opened goes to a fresh worker.** `AppServices.openSession` (used by Open project,
Restart code and Apply and restart) drops the link, which closes its worker process, and opens another, with the session
asked for. The first session of a link is opened on it as it is. The reason is a measurement: a worker keeps about 20 MB of
every world it held (`docs/architecture/app.md`, "Why Restart code starts a new worker"), so a team restarting all day
would end with a gigabyte worker. A new worker costs a few seconds, during which the viewport says it is connecting.

`FakeSimulatorLink` is an in-process link for developing and testing without a worker: a 12 ft field with walls, two
goals, a barrier, a turnstile and two kinds of piece, a kinematic chassis that gamepad 1 drives while an OpMode runs,
four canned OpModes (a teleop, a self-driving square, a telemetry spammer, one that crashes after three seconds),
telemetry, hardware readouts, score, problems and human actions. It tells its listeners its **catalogue** (three fields,
`none`, `decode` and `biobuzz`, with the start poses and human actions of each, and two motors) from the link thread right
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

The viewport is shown only while the Simulator screen is composed. When it leaves, `ViewportEngine.setShown(false)` makes
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
- **The robot is driven only while the Simulator screen is shown**, the window is active and no modal is open
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

`%LOCALAPPDATA%\FTC-Sim\config\ui.properties` (the application keeps its natives, logs and programs in `%LOCALAPPDATA%\FTC-Sim` too; there is no
Java helper for it there, so `UserDirectories` is a few lines in this module). A UTF-8 properties file with `format=1`:
`display.*` (render scale, frame cap, overlay, an old `lengthInches` is ignored), `gamepad.N.*` (assignment kind, controller GUID and name, dead zones),
`keyboard.<CONTROL>=<AWT key code>` (0 is unbound), `project.N.*` (a recent project: its folder, name and, when it was run, the
field, alliance and start pose of that run, the OpMode that finds its hardware, and `robotSkipped`; an older `recent.N.*` run list,
the `trusted.N` keys and the `run.seed` and `run.randomSeed` keys of earlier versions are ignored, not read).
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
flow: Projects, a project folder opened (the fake build running, the question for the game, the Robot page, the simulator starting by itself), and on the Simulator a match armed,
started and stopped, and a restart; it writes a screenshot of every
step. Others start a run from the Setup tab, click the viewport with a synthetic pointer event, hold W with a
synthetic key event, check the robot drove and that opening a dialog released the key, that leaving the Simulator releases the
keys, stops drawing and keeps the run going, that a half-size picture still shows the field, that the performance overlay
measures the real render thread, and check pixels (the robot in its alliance's colour, green and purple pieces, many shades). They write
`build\screens\*.png` (every screen, the Controls page at full length). Without an OpenGL 3.3 context the
OpenGL-dependent tests are skipped, not failed.

`AppFlowTest` drives the whole interface on the fake host with synthetic clicks (no OpenGL): Open project to the game
question, the Robot page (a mapping on a file, a mapping created by clicks) and the Simulator, with what was chosen being what starts; Init, away to
Controls and back with the run intact; a recent project reopening with one click; **Restart code** with its strip; a build
that fails; a folder that is neither a project nor a program refused in place and dismissed; a project refused while
another is being built; the last project opened at start and a missing one said on Projects; the
FAKE HOST banner; a link that cannot be had and Try again; and settings reaching the engine and the key map.
`ProjectRunnerTest` (on a scripted preparer and stub links, with bounded waits on state) covers the runner's
states: building, needing the game, failing, cancelling, a source that changes during the build, a restart
keeping the run, the robot mapped, skipped, undescribed or in a file that is not read, the mapping that travels in the request, the
messages and problems it emits and saving the run defaults. `BuildViewTest` shows the build view (running with its step line, cancelling, failed with and without a
build log, the log following the newest line and stopping when scrolled up), `ProjectListTest` the recent projects,
`RestartStripTest` the strip, and `MappingModelTest` and `MappingScreenTest` the Robot page (placing and removing by click
and by the drag's own callbacks, what a place refuses, the model and Mirrored of a motor, what is wrong and where, what makes
the page differ from its file, the Hub and Limelight forms), all on the fake preparer played by a `ManualScheduler` or on a scripted one where exact events
matter. `AppServicesTest` covers the host: the link opening off
the UI thread, a link that cannot be had and the retry, a session asked for early, a failed session on a fresh link, and the
link being closed with the window, all with bounded waits on state and no sleeps. `PracticeScreenTest` covers the match
controls in every state and refusals; `StartPanelTest` the Setup tab (the poses of an alliance, custom numbers, applying
on a fresh worker with the seed kept, saving the defaults); `PracticeSessionTest` the console (lines in order, the newest kept, a new session starting it again). Each other screen has a Compose UI test with synthetic events (clicks, text,
key presses, hover for the tooltip, semantic slider actions, mouse-wheel scrolling), including Tab order on Projects and the
rebinding of a key; the pure parts (routing, mapping, settings codec and store, form validation and model, navigator,
the prepare model and output log, match display, key rules) are unit tests with no Compose.

The Robot page (`dev.ftcsim.ui.robot`) has unit tests for `MappingModel` (including loading a mapping, editing it and changing nothing) and
`MappingScreenTest` with synthetic events; `HardwareScanTest` (on a stub link: Init and Stop, the names found, the
choice remembered and rerun by itself, an OpMode that never initialises, Cancel). `OrbitCameraTest` also checks that `project`, `ray` and `pan` agree with the GL matrix. A real mouse drag is not
tested; the gesture is tested with synthetic events.

`FieldRendererTest` looks at the renderer's own pixels on a small offscreen target (skipped without OpenGL): the field's floor
keeps its colour and shows seams and no glare, a shadow dims the floor by a third and two shadows on one spot are one
shadow, a tag is a black border, a white square and a black core, the picture comes back the right way up, and a frame
reports what it asked the GPU to draw. `OrbitCameraTest` and `SceneModelTest` hold the camera and scene rules without
OpenGL (which way the driver looks, the chase camera stopping short of a goal or going round to its side, the floor and tag
detection).

`RealFieldViewportTest` (in `acceptance-tests`, because it needs a real `SimulationSession`) is the harness for how the
real fields look: it opens Hornet's mapping on DECODE and BIOBUZZ, draws their description and snapshots with the
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
  the box (a piece carried up Hornet's conveyor is drawn above the box). Drawing the design's wheels and mechanisms would fix this.
- `FieldDescription` does not say which way the driver stands, so Top and Driver use the start pose's heading when it is
  square to the walls and the audience's end (-X) otherwise; a field that says so would remove the guess.
- The real file chooser (`AwtFileChooser`) and log opener (`AwtLogOpener`), and the real preparer and link, are only
  exercised by the application around the window; here they are behind interfaces and replaced by fakes.
- The progress line says only what the build is doing in the preparer's words, and not how far along it is: the preparer
  reports steps, not a fraction.
- A timed match has no start countdown, no buzzer and no sound; the field's own scoring decides the final score.
- Theming beyond the standard M3 schemes, a collapsible or resizable details panel, a Windows-style folder chooser (Swing's
  chooser in the Windows look is used; the native one cannot pick folders).
- Rumble and LED feedback to controllers; controller hot-plug and GUID matching on hardware are untested (no controller
  was attached): the router and screens are tested with synthetic readings.
- Any measurement on a display faster than 60 Hz or on laptop-class hardware beyond what `viewport-measurements.md` holds,
  and a benchmark task; the performance overlay is for a person to look at.
