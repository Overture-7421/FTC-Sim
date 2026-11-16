# FTC-Sim

A simulator for FIRST Tech Challenge robot code, for the 2026-27 season. It runs a team's own robot program, unmodified,
on a simulated robot and a simulated field, so that the code can be tested and the drivers can practise on a laptop. It
simulates hardware and the Android environment around it and nothing else: driving, path following, mechanism logic and
command scheduling stay the team's code.

- **Windows 10 or 11, x64.** No other platform is supported.
- **FTC SDK 11 and 12.** The robot program is built against its own SDK; the simulator runs that SDK's real classes
  (`docs/sdk-versions.md`: the five published releases 11.0.0, 11.1.0, 11.2.0, 11.2.1 and 12.0.0 are in the test matrix).
- **Two games:** DECODE (2025-26) and BIOBUZZ (2026-27), each as a physics field with its scoring.
- **Virtual time.** The robot program lives in simulated time: pausing, slowing or speeding up a run does not change what
  it sees, and a loop iteration costs simulated time, not however long the machine took. The clock the program reads is
  the simulator's. Runs are not promised to repeat bit for bit.
- **Material 3 window, drawn up to 144 Hz.** Rendering never changes the simulation.
- **A driver needs no Java.** The zip carries its own Java 25 runtime (`runtime\`, 51 MB unpacked), and `FTC-Sim.cmd`
  starts the application with it. Nothing is downloaded and nothing is installed.

## Using it

**Drivers.** Unzip `FTC-Sim.zip` anywhere (Extract All, not from inside the zip) and double-click `FTC-Sim.cmd`. FTC-Sim
opens the robot project that was open last time. Map two gamepads or the keyboard on the Controls page, and press
Init and Start on the Simulator page. The field, the alliance and where the robot starts are in the Simulator's Setup tab,
so you can change them while you test. What went wrong is in the Problems tab, and in
`%LOCALAPPDATA%\FTC-Sim\logs`, the newest `app-*.log` and `worker-*.log`.

**Opening your robot project.** Projects, Open project, and choose the team's Android Studio project folder, the one with
`settings.gradle` in it. FTC-Sim builds it with its own Gradle build on a JDK 17 to 21 and an Android SDK found on the PC
(Android Studio brings both) and runs it; the original folder is only read, and its hashes are checked before and after.
The team's build scripts and code run on the PC while this happens: choosing the folder is the consent. The window shows
the step the build is on; a project that has not been run before is asked for its game once (BIOBUZZ or DECODE), and the
simulator starts by itself when the build is done. Open projects are listed on Projects; the next start opens the last one
again by itself.

**Changing the code.** Edit it as usual, then press **Restart code** in the Simulator's top bar. FTC-Sim builds what is on
disk now (a fraction of a second when nothing changed) and starts the simulation again with it, on the same field, alliance
and start pose. Nothing watches your files: you decide when the simulator picks your changes up.

**The robot.** The robot of each game (DECODE, BIOBUZZ) is fixed: four mecanum wheels, an intake roller, a conveyor and a
shooter, and a turret for BIOBUZZ. A program only names its hardware, so you map each motor name onto a part of that robot, in
`ftcsim.json` at the root of your project. A project without one runs a robot with no motors mapped (the simulator assumes no motor names) and opens the **Robot** screen:
pick an OpMode, and drag each motor the code asks for onto a wheel, the Intake, Conveyor, Shooter or Turret; choose each
motor's model and whether it is mirrored, fill in the robot's mass, wheel diameter, hub mounting and Limelight mount, and the file is written for you.
Commit it, so that your teammates run the same robot; the field, alliance and start pose of a run can be saved into it too
(Simulator, Setup tab, Save as default). The same page shows what the file maps and edits it (`docs/robots/fixed-robots.md`). A motor
a program asks for that the file does not mention still works and is reported as unmapped; the Robot page lists such names.

## Building it

JDK 25 is forced and fetched when missing; `gradlew.bat` needs only some JDK 17 or newer to start. `:app:dist` makes the
distribution's Java runtime with that JDK's `jlink`.

```
.\gradlew.bat :app:run        the real application from source (window, worker process, importer)
.\gradlew.bat :desktop-ui:runFake   the window alone on a fake host, for interface work (nothing real runs)
.\gradlew.bat :app:dist       app\build\dist\FTC-Sim and FTC-Sim.zip, what drivers get
.\gradlew.bat check           every module's tests, the module rules, the distribution
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
| `sim-hardware` | REV hub firmware emulated at the RHSP wire, and the fixed ideal sensors: goBILDA Pinpoint, REV Color V3, the Control Hub's BNO055 IMU, Limelight 3A (each found by the driver class the code asks for; anything else is a problem of the session). |
| `sim-physics` | Jolt physics, mecanum traction. |
| `games` | The field interface (`dev.ftcsim.field`) and the two fields, DECODE and BIOBUZZ, one package each. |
| `simulator`, `sim-link`, `worker` | A session (with the plants, intakes and launchers), the link protocol, and the process that runs it. |
| `project-import` | Turns a team's project into a built program for the simulator without touching it. |
| `desktop-ui`, `app` | The window, and the composition root with the launcher (`FTC-Sim.cmd`) and the distribution's Java runtime. |
| `acceptance-tests` | Tests across the modules, including Hornet's unmodified code on both fields. |

`docs/README.md` is the index of the documentation. **If you are going to change the code** (a person or an agent), read
`AGENTS.md` and `docs/development.md` first. `docs/architecture/overview.md` shows how the parts fit together and where to
look for what, and `docs/architecture/decisions.md` says why it is built this way: one JVM stack with a native physics
engine (no Rust sidecar), two class loaders, virtual time, hardware emulated at the wire. The other documents:

- `docs/development.md`, `docs/testing.md`, `docs/vscode.md`: working on the project, what each layer of tests proves, the editor setup.
- `docs/roadmap.md`: what is missing, by area, and where to begin.
- `docs/architecture/adding-a-device.md`: the fixed ideal sensors, how a driver class is bound to one, how to add another.
- `docs/architecture/app.md`, `link.md`, `ui.md`, `distribution.md`, `prepared-program.md`: the pieces of the application.
- `docs/fields/decode.md`, `biobuzz.md`: what each field models, what is estimated and what is left out.
- `docs/robots/fixed-robots.md`, `hornet-profile.md`: the fixed robots, the mapping a team supplies and the turret, and how Hornet's numbers were fitted.
- `docs/devices/`: the Pinpoint, the colour sensor, the IMU and the Limelight camera, and what each reports.
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

What it cannot run yet: webcams and VisionPortal (no camera frames; the lookup is refused by name and the session lists it as
a problem), I2C sensors other than the Pinpoint, the REV colour sensor and the Control Hub's IMU (an OctoQuad, an external
IMU, a second sensor of a kind, the REV 2 m distance sensor: unsupported, not bound and listed among the session's problems),
the Control Hub's newer BHI260 IMU (a BNO055 stands in: programs that ask for the `IMU` interface cannot tell), and motors
beyond the goBILDA Yellow Jacket family in the catalogue. Each is one model or one catalogue entry
(`docs/architecture/adding-a-device.md`).

What has not been measured, because it needs other machines: performance on a four-core integrated-GPU laptop and on a
144 Hz display, the distribution on a clean PC under a school
policy, and hot-plugging real controllers. `docs/architecture/distribution.md` and `ui.md` list what is and is not verified.
`docs/roadmap.md` lists every known limit by area, with where to begin.

FTC-Sim's own code has no licence yet: the owner has to choose one. The distribution carries the third-party notices.
