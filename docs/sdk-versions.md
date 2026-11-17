# FTC SDK 11 and 12

Which FTC SDK releases FTC-Sim runs, with the evidence; for anyone supporting a team on a given SDK or adding a release.

The simulator bundles no FTC SDK. The importer packs the SDK jars the team's project was built with into the prepared program, and the robot world runs those real classes. FTC-Sim adds the host around them (`sdk-common`, compiled once against 11.1.0), the Android shim and the hubs behind the wire. "Supported" means a program built against that SDK runs on the same adapter.

## What was checked

| Check | 11.0.0 | 11.1.0 | 11.2.0 | 11.2.1 | 12.0.0 |
|---|:-:|:-:|:-:|:-:|:-:|
| Fixture suite (`:acceptance-tests:test -PftcSdk=<version>`): SDK boot, OpMode lifecycle, runaway OpModes, hub and bulk caching, motors and servos, IMU, unmapped pins, Pinpoint, colour sensor, sensors by driver class, Limelight, mechanisms, the turret fixture, sessions and matches | 178 pass | 178 pass | 178 pass | 178 pass | 178 pass |
| Hornet (12 OpModes, autos, teleop, tuners, both fields) | | 18 pass in `acceptance-tests` (2026-11-17; `HornetImportTest` not re-run) | | | 9 pass, rehearsal below (2026-11-15, not re-run) |

Everything else ran on 2026-11-17 (the fixture suite includes the BIOBUZZ stub, which moves every mechanism of the robot and reads the OctoQuad through each release's `OctoQuadImpl`). 11.1.0 is the build's default.

- The acceptance suite has 196 tests. 18 need a prepared Hornet program (and `HornetImportTest` in `project-import` needs a checkout); they skip without one, leaving the 178.
- The 19 Hornet tests are all those needing the checkout, run with it on 11.1.0. The 9 on 12.0.0 are the rehearsal.
- To run the matrix: `.\gradlew.bat :acceptance-tests:test -PftcSdk=<version>` (quote it in PowerShell: `'-PftcSdk=12.0.0'`). The build accepts every `ftcSdk*` version of `gradle/libs.versions.toml` and refuses any other, so a new release needs one catalog line ([development.md](development.md), recipes).
- SDK 12 moved the AprilTag singleton fields to `AprilTagSingleDetection`. That touches the vision portal, which the simulator does not run, not the Limelight, which speaks REST.

## Hornet on SDK 12

Hornet targets SDK 11.1.0 and cannot move on its own: SolversLib 0.3.4 declares its FTC libraries `{strictly 11.1.0}`, so Gradle refuses `RobotCore:12.0.0`. A team that moves needs a SolversLib built for SDK 12. To see whether FTC-Sim would cope, the upgrade was rehearsed once by hand:

1. Take the FIRST template of the release (`FIRST-Tech-Challenge/FtcRobotController`, tag `v12.0`; Gradle 9.1.0, Android Gradle plugin 8.13.2). Replace its `TeamCode` with Hornet's, unchanged, with Hornet's two Maven repositories, its Pedro, Panels and SolversLib dependencies and `compileSdk 34`.
2. Override SolversLib's pin: `configurations.configureEach { resolutionStrategy.eachDependency { if (it.requested.group == 'org.firstinspires.ftc') it.useVersion '12.0.0' } }`. This is a forced binary upgrade (libraries built for 11.1.0 run on 12.0.0 classes). It is **not a statement that SolversLib supports SDK 12**.
3. Prepare that folder with the importer and run the Hornet tests on it: `-Pftcsim.hornet=<that folder> -Pftcsim.hornetPackage=<a new folder>`.

Result: the importer builds it with the project's own wrapper on a JDK 21; the package holds the SDK 12.0.0 jars; the host lists the same 12 OpModes; each initialises, starts and stops; the autos score as on 11.1.0 (Close Auto 15, Far Auto 9, both alliances). Nothing in FTC-Sim had to change.

## When a program fails on one SDK only

The simulator reports what the SDK reports. Start from the session's problem list and the worker's log (`%LOCALAPPDATA%\FTC-Sim\logs`): a class the shim cannot load, an Android call the shim lacks, or a device the mapping does not wire is named there with the member and its caller.
