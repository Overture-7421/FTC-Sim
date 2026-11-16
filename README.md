# FTC-Sim

A simulator for FIRST Tech Challenge robot code, for the 2026-27 season. It runs a team's own robot program, unmodified,
on a simulated robot and a simulated field, so that the code can be tested and the drivers can practise on a laptop. It
simulates hardware and the Android environment around it and nothing else: driving, path following, mechanism logic and
command scheduling stay the team's code.

- **Windows 10 or 11, x64.** No other platform is supported. A driver needs no Java: the zip carries its own Java 25
  runtime, and `FTC-Sim.cmd` starts the application with it.
- **FTC SDK 11 and 12.** The program is built against its own SDK and the simulator runs that SDK's real classes
  (`docs/sdk-versions.md`: 11.0.0, 11.1.0, 11.2.0, 11.2.1 and 12.0.0 are in the test matrix).
- **Two games:** DECODE (2025-26) and BIOBUZZ (2026-27), each as a physics field with its scoring.
- **Virtual time.** The program lives in simulated time: pausing, slowing or speeding up a run does not change what it
  sees, and a loop iteration costs simulated time, not however long the machine took. Runs are not promised to repeat bit
  for bit.
- **Material 3 window, drawn up to 144 Hz.** Rendering never changes the simulation.
- **Not done, on purpose:** foul and ranking-point rules, replay of a run, opposing or second robots, air drag, a browser
  dashboard for Panels, TOML or YAML robot profiles, macOS or Linux. Mechanisms never push on the chassis. Physics is
  accurate enough to test code and practise driving, not to design a robot. Webcams and `VisionPortal`, other I2C sensors
  than the fixed ones and other motors than the catalogue's are not supported yet: `docs/roadmap.md`.

## Using it

**Drivers.** Unzip `FTC-Sim.zip` anywhere (Extract All, not from inside the zip) and double-click `FTC-Sim.cmd`. FTC-Sim
opens the robot project that was open last time. Map two gamepads or the keyboard on the Controls page, and press
Init and Start on the Simulator page. The field, the alliance and where the robot starts are in the Simulator's Setup tab,
so you can change them while you test. What went wrong is in the Problems tab, and in `%LOCALAPPDATA%\FTC-Sim\logs`, the
newest `app-*.log` and `worker-*.log`.

**Opening your robot project.** Projects, Open project, and choose the team's Android Studio project folder, the one with
`settings.gradle` in it. FTC-Sim builds it with its own Gradle build on a JDK 17 to 21 and an Android SDK found on the PC
(Android Studio brings both) and runs it; the original folder is only read, and its hashes are checked before and after.
The team's build scripts and code run on the PC while this happens: choosing the folder is the consent. A project that has
not been run before is asked for its game once (BIOBUZZ or DECODE), and the simulator starts by itself when the build is
done.

**Changing the code.** Edit it as usual, then press **Restart code** in the Simulator's top bar. FTC-Sim builds what is on
disk now and starts the simulation again with it, on the same field, alliance and start pose. Nothing watches your files:
you decide when the simulator picks your changes up.

**The robot.** The robot of each game is fixed: four mecanum wheels, an intake roller, a conveyor and a shooter, and a
turret for BIOBUZZ. A program only names its hardware, so you map each motor name onto a part of that robot in `ftcsim.json`
at the root of your project. A project without one runs with no motors mapped and opens the **Robot** screen: pick an
OpMode, drag each motor the code asks for onto a wheel, the Intake, Conveyor, Shooter or Turret, set each motor's model and
whether it is mirrored, the robot's mass, wheel diameter, hub mounting and Limelight mount, and the file is written for
you. Commit it, so that your teammates run the same robot; a run's field, alliance and start pose can be saved into it too
(Simulator, Setup tab, Save as default). A motor the file does not mention still works and is reported as unmapped
(`docs/robots/fixed-robots.md`).

## Building it

```
.\gradlew.bat :app:run        the real application from source (window, worker process, importer)
.\gradlew.bat :desktop-ui:runFake   the window alone on a fake host, for interface work (nothing real runs)
.\gradlew.bat :app:dist       app\build\dist\FTC-Sim and FTC-Sim.zip, what drivers get
.\gradlew.bat check           every module's tests, the module rules, the distribution
```

JDK 25 is forced for the build and fetched when missing; `gradlew.bat` needs only some JDK 17 or newer to start. Never run
`gradlew clean`: several tests read the build folders. `docs/development.md` has the details, the hazards and the tests
that need Hornet; `docs/vscode.md` has the same as tasks and launch configurations in VS Code.

## Where the documentation is

`docs/README.md` is the index. **If you are going to change the code** (a person or an agent), read `AGENTS.md` and
`docs/development.md` first; `docs/architecture/overview.md` shows how the parts fit together (the modules, the threads,
where to look for what) and `docs/architecture/decisions.md` says why it is built this way. `docs/roadmap.md` lists what
is missing and where to begin.

## Proof and limits

Hornet (`Overture-7421/FTC-Hornet-2026`, with Pedro Pathing, SolversLib and Panels) runs unmodified: all 12 of its OpModes
initialise, start and stop on both fields; Pedro's own tuners measure the simulated drivetrain within 1.4 % of the real
robot's constants; its autonomous routines score 15 and 9 artifacts in 30 s on DECODE and its teleop drives, intakes
and shoots. On BIOBUZZ it runs but cannot score: its camera map, its intake and its aim are DECODE's.

Not measured, because it needs other machines: performance on a four-core integrated-GPU laptop and on a 144 Hz display,
the distribution on a clean PC under a school policy, and hot-plugging real controllers (`docs/architecture/distribution.md`
and `ui.md` list what is and is not verified).

FTC-Sim's own code has no licence yet: the owner has to choose one. The distribution carries the third-party notices.
