# FTC-Sim

A simulator for FIRST Tech Challenge robot code, for the 2026-27 season. It runs a team's own robot program, unmodified,
on a simulated robot and a simulated field, so that the code can be tested and the drivers can practise on a laptop. It
simulates hardware and the Android environment around it and nothing else: driving, path following, mechanism logic and
command scheduling stay the team's code.

- **Windows 10 or 11, x64.** No other platform is supported.
- **FTC SDK 11 and 12.** The robot program is built against its own SDK; the simulator runs that SDK's real classes
  (`docs/sdk-versions.md`: the five published releases 11.0.0, 11.1.0, 11.2.0, 11.2.1 and 12.0.0 are in the test matrix).
- **Two games:** DECODE (2025-26) and BIOBUZZ (2026-27), each as a physics field with its scoring.
- **Deterministic.** The same inputs on the same machine and build give the same run, whatever the frame rate, the
  speed of the machine or how time is sliced. Time, randomness and threads the robot program sees are the simulator's.
- **Material 3 window, drawn up to 144 Hz.** Rendering never changes the simulation.
- **A driver needs no Java.** `FTC-Sim.cmd` finds or downloads the exact Temurin build it pins (25.0.5+8, verified by
  its SHA-256) and starts the application with it.

## Using it

**Drivers.** Unzip `FTC-Sim.zip` anywhere (Extract All, not from inside the zip) and double-click `FTC-Sim.cmd`. A
programmer on the team prepares the robot program once (below); drivers then pick the program, the field and the
alliance on the Setup page, map two gamepads or the keyboard on the Controls page, and press Init and Start on the
Practice page. What went wrong is in `%LOCALAPPDATA%\FTC-Sim\logs`, the newest `app-*.log` and `worker-*.log`.

**Preparing a robot program.** Home, Open project, and choose the team's Android Studio project folder. The project is
copied, built with its own Gradle build on a JDK 17 to 21 and an Android SDK found on the PC (Android Studio brings
both), and turned into a prepared program under `%LOCALAPPDATA%\FTC-Sim\programs`; the original folder is only read, and
its hashes are checked before and after. Preparing again from the same source takes a fraction of a second. The team's
build scripts and code run on the PC while this happens, which is why the window asks for consent first.

**The robot.** A program only names its hardware; the robot profile says what a name is made of. The one that ships is
Hornet's. To simulate another robot, write its profile (`docs/robots/robot-profile.md`) and choose the file on the Setup
page. A device a program asks for that the profile does not mention still works and is reported as unbound.

## Building it

JDK 25 is forced and fetched when missing; `gradlew.bat` needs only some JDK 17 or newer to start.

```
.\gradlew.bat :app:run        the real application from source (window, worker process, importer)
.\gradlew.bat :app:dist       app\build\dist\FTC-Sim and FTC-Sim.zip, what drivers get
.\gradlew.bat check           every module's tests, the module rules, the launcher's tests
.\gradlew.bat :acceptance-tests:test -Pftcsim.hornetPackage=<prepared Hornet program> [-PftcSdk=12.0.0]
```

`docs/vscode.md` has the same as tasks, launch configurations and debugging in VS Code. Never run `gradlew clean`;
several of the tests read the build folders.

## Where things are

| Module | What it is |
|---|---|
| `sim-contracts` | The only code the two sides share: units, snapshots, the link and robot-hook interfaces. |
| `sim-core` | The virtual-time kernel: one robot thread, one simulator thread, a baton between them. |
| `robot-runtime`, `android-shim`, `sdk-common` | The robot world: bytecode rewriting of clocks, sleeps and threads, a small `android.*`, and the SDK host and hardware map. |
| `sim-hardware` | REV hub firmware emulated at the RHSP wire, and the device models: goBILDA Pinpoint, REV Color V3, OctoQuad MK2, BNO055 IMU (a Control Hub has one without a profile line), digital and analog pins (limit switches, touch sensors, potentiometers), Limelight 3A. |
| `sim-physics`, `sim-mechanisms`, `sim-field` | Jolt physics, mecanum traction, plants, intakes, launchers and the field interface. |
| `game-decode`, `game-biobuzz` | The two fields. |
| `simulator`, `sim-link`, `worker` | A session, the link protocol, and the process that runs it. |
| `project-import` | Turns a team's project into a prepared program without touching it. |
| `desktop-ui`, `app`, `launcher` | The window, the composition root and the start-up scripts that provision the JDK. |
| `acceptance-tests` | Tests across the modules, including Hornet's unmodified code on both fields. |

`docs/README.md` is the index of the documentation. **If you are going to change the code** (a person or an agent), read
`AGENTS.md` and `docs/development.md` first. `docs/architecture/overview.md` shows how the parts fit together and where to
look for what, and `docs/architecture/decisions.md` says why it is built this way: one JVM stack with a native physics
engine (no Rust sidecar), two class loaders, virtual time, hardware emulated at the wire. The other documents:

- `docs/development.md`, `docs/testing.md`, `docs/vscode.md`: working on the project, what each layer of tests proves, the editor setup.
- `docs/roadmap.md`: what is missing, by area, and where to begin.
- `docs/architecture/adding-a-device.md`: add a hardware model; one class and one registry line (the OctoQuad is the example).
- `docs/architecture/app.md`, `link.md`, `ui.md`, `distribution.md`, `prepared-program.md`: the pieces of the application.
- `docs/fields/decode.md`, `biobuzz.md`: what each field models, what is estimated and what is left out.
- `docs/robots/robot-profile.md`, `hornet-profile.md`: the robot profile format, and how Hornet's was fitted.
- `docs/devices/`: the IMU, the digital and analog pins and the Limelight camera, and what each reports.
- `docs/sdk-versions.md`: which FTC SDK releases were run, and how Hornet was run on the SDK 12 template.

## Proof and limits

Hornet (`Overture-7421/FTC-Hornet-2026`, with Pedro Pathing, SolversLib and Panels) runs unmodified: all 12 of its OpModes
initialise, start and stop on both fields; Pedro's own tuners measure the simulated drivetrain within 1.4 % of the real
robot's constants; its autonomous routines score 15 and 9 artifacts in 30 s on DECODE and its teleop drives, intakes
and shoots. On BIOBUZZ it runs but cannot score: its camera map, its intake and its aim are DECODE's. The simulator
changes none of that.

What it does not do, on purpose: foul and ranking-point rules, replay of a run, opposing robots, a second robot, air
drag, a browser dashboard for Panels, TOML or YAML robot profiles, macOS or Linux. Mechanisms never push on the chassis.
Physics is accurate enough to test code and practise driving, not to design a robot.

What it cannot run yet: webcams and VisionPortal (no camera frames; the lookup is refused by name), I2C sensors that have
no model (the REV 2 m distance sensor, for one: its driver finds an empty bus, as with an unplugged sensor), the Control
Hub's newer BHI260 IMU (a BNO055 stands in: programs that ask for the `IMU` interface cannot tell), and motors beyond the
goBILDA Yellow Jacket family in the profile's catalogue. Each is one model or one catalogue entry
(`docs/architecture/adding-a-device.md`).

What has not been measured, because it needs other machines: the golden physics hashes on an Intel CPU and on a second AMD
CPU, performance on a four-core integrated-GPU laptop and on a 144 Hz display, the launcher on a clean PC under a school
policy, and hot-plugging real controllers. `docs/architecture/distribution.md` and `ui.md` list what is and is not verified.
`docs/roadmap.md` lists every known limit by area, with where to begin.

FTC-Sim's own code has no licence yet: the owner has to choose one. The distribution carries the third-party notices.
