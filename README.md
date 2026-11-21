# FTC-Sim

A simulator for FIRST Tech Challenge robot code. It runs a team's own robot program, unmodified, on a simulated robot and
field, so code can be tested and drivers can practise on a laptop. It simulates hardware and the Android environment
around it and nothing else: driving, path following and mechanism logic stay the team's code.

- **Windows 10/11 x64 only.** Drivers need no Java: the zip carries its own Java 25 runtime.
- **FTC SDK 11 and 12.** The program runs against its own SDK's real classes (`docs/sdk-versions.md`).
- **Two games:** DECODE (2025-26) and BIOBUZZ (2026-27), each a physics field with scoring.
- **Virtual time.** Pausing, slowing or speeding a run does not change what the program sees. Runs are not bit-for-bit
  repeatable.
- **Material 3 window, drawn up to 144 Hz.** Rendering never changes the simulation.
- **Panels dashboard.** A program that carries Panels serves its dashboard on ports 8001 and 8002 of this PC, on the loopback address only; **Open dashboard** in the Simulator's top bar opens it in the browser.
- **Not done, on purpose:** foul and ranking rules, replay, a second or opposing robot, air drag, macOS and Linux. Physics is accurate enough to test code and practise, not to design a robot. Webcams,
  `VisionPortal` and sensors beyond the fixed ones are not supported yet (`docs/roadmap.md`).

## Using it

**Start.** Download `FTC-Sim-<version>-Setup.exe` from the [latest release](https://github.com/Overture-7421/FTC-Sim/releases/latest)
and run it (it installs for you alone, no administrator rights; Windows may warn once that the publisher is unknown: More
info, Run anyway), then start FTC-Sim from the Start menu. The zip in the release also works: Extract All and double-click
`FTC-Sim.cmd`. FTC-Sim offers a newer release in its window and installs it when you say so (`--no-update-check` turns the
look off; `docs/architecture/updates.md`). It opens the last project. Map two gamepads or
the keyboard on **Controls**, then press Init and Start on **Simulator**. Field, alliance and start pose are in the
Simulator's Setup tab. Problems show in the Problems tab and in `%LOCALAPPDATA%\FTC-Sim\logs` (newest `app-*.log` and
`worker-*.log`).

**Open a project.** Projects, Open project, then pick the Android Studio project folder (the one with `settings.gradle`).
FTC-Sim builds it with its own Gradle on a JDK 17-21 and an Android SDK found on the PC (Android Studio brings both). The
folder is only read. The team's build scripts and code run on the PC, so choosing the folder is the consent. The first
run asks for the game once.

**Change the code.** Edit as usual, then press **Restart code** in the Simulator's top bar. Nothing watches your files.

**The robot.** Hornet's and BIOBUZZ's robots are built into the simulator (BIOBUZZ's on its field, Hornet's on every
other): four mecanum wheels, intake, conveyor and shooter, plus a turret, hood, stopper and colibri arms for BIOBUZZ.
Your program finds them by the hardware names of those robots; nothing is mapped and no file is added to your project. A
name the robot lacks is reported, not fatal (`docs/robots/fixed-robots.md`).

## Building it

```text
.\gradlew.bat :app:run              the real application from source
.\gradlew.bat :desktop-ui:runFake   the window alone on a fake host (interface work)
.\gradlew.bat :app:dist                  app\build\dist\FTC-Sim and FTC-Sim.zip
                                        (a release: -PappVersion=1.2.3; the installer is built by the release workflow, docs/architecture/updates.md)
.\gradlew.bat check                 all tests, module rules, distribution
```

JDK 25 is used for the build and fetched when missing; `gradlew.bat` itself needs a JDK 17 or newer. Never run
`gradlew clean`. Details: `docs/development.md` (and `docs/vscode.md` for VS Code).

## Documentation

`docs/README.md` is the index. To change the code, read `AGENTS.md`, `docs/development.md` and
`docs/architecture/overview.md` first.

## Proof and limits

Hornet (`Overture-7421/FTC-Hornet-2026`: Pedro Pathing, SolversLib, Panels) runs unmodified. All 12 OpModes initialise,
start and stop on both fields. Pedro's tuners measure the simulated drivetrain within 1.4% of the real robot's constants.
Its autos score about 15 and 9 artifacts in 30 s on DECODE (the tests require 12 and 7) and teleop drives, intakes and
shoots. On BIOBUZZ it runs but cannot score: its camera map, intake and aim are DECODE's.

Not measured, because it needs other machines: a four-core integrated-GPU laptop, a 144 Hz display, a clean PC under a
school policy, and hot-plugged controllers (`docs/architecture/distribution.md`, `docs/architecture/ui.md`).

FTC-Sim's own code has no licence yet; the owner has to choose one. The distribution carries third-party notices.
