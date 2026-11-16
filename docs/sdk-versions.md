# FTC SDK 11 and SDK 12

The simulator never bundles an FTC SDK. A robot program brings its own: the importer packs the SDK jars the team's
project was built with into the prepared program, and the simulator runs those real classes (`OpModeManagerImpl`,
the Lynx module and its drivers, `HardwareMap`, the device drivers) in the robot world. What FTC-Sim adds is the host
around them (`sdk-common`, compiled once against 11.1.0), the Android shim and the hubs behind the wire. So "supported"
means: a program built against that SDK runs, and the same adapter serves all of them.

## What was checked

| Check | SDK 11.0.0 | 11.1.0 | 11.2.0 | 11.2.1 | 12.0.0 |
|---|:-:|:-:|:-:|:-:|:-:|
| The fixture suite (`:acceptance-tests:test -PftcSdk=<version>`): SDK boot, OpMode lifecycle, runaway OpModes, hub and bulk caching, motors and servos, the IMU, unmapped pins, the Pinpoint, the colour sensor, sensors by driver class, the Limelight, mechanisms and the turret fixture, sessions and matches on the real SDK | not re-run since the sensors became fixed | 169 pass | not re-run | not re-run | 169 pass |
| Hornet, a real program (Pedro Pathing, SolversLib, Panels, Limelight): 12 OpModes, autos, teleop, tuners, both fields | | 193 pass | | | 9 pass, see below |

The SDK 11.1.0 column is the default of the build. The suite has 189 tests (2026-11-15); 20 of them need a prepared Hornet program and
are skipped without one, which leaves the 169 of the first row. The Hornet row (193 on 11.1.0, 9 on 12.0.0) is from before the sensors
became fixed and ideal and has not been re-run since: the Hornet gate has to be run again. The 9 of the SDK 12.0.0
column in the second row are the rehearsal below: the bring-up and autonomous tests on a Hornet project moved to the
SDK 12 template. Run the matrix with `.\gradlew.bat :acceptance-tests:test -PftcSdk=<version>` (the build refuses a
version that is not in `gradle/libs.versions.toml`; quote the property in PowerShell: `'-PftcSdk=12.0.0'`). SDK 12's AprilTag
API break (singleton fields moved to `AprilTagSingleDetection`) touches the vision portal, which the simulator does not run, and
not the Limelight, which speaks REST.

## Hornet on SDK 12

Hornet is written for SDK 11.1.0 and cannot move to SDK 12 on its own: SolversLib 0.3.4 declares the FTC libraries
it needs as `{strictly 11.1.0}`, so Gradle refuses `RobotCore:12.0.0` next to it ("Cannot find a version of
`org.firstinspires.ftc:RobotCore` that satisfies the version constraints"). A team that moves needs a SolversLib built
for SDK 12. To see whether FTC-Sim would cope when that happens, the upgrade was rehearsed by hand, the way a team does it:

1. Take the FIRST template of the new release (`FIRST-Tech-Challenge/FtcRobotController`, tag `v12.0`): its
   `FtcRobotController` module, `build.gradle` (Gradle 9.1.0, Android Gradle plugin 8.13.2), `build.common.gradle`,
   `build.dependencies.gradle`, wrapper and `TeamCode/build.gradle` (the plugin is applied there now, not in
   `build.common.gradle`).
2. Put Hornet's `TeamCode` folder (its own code, unchanged) in place of the template's, add Hornet's two Maven
   repositories and its Pedro, Panels and SolversLib dependencies to `build.dependencies.gradle`, and keep Hornet's
   `compileSdk 34`.
3. Override SolversLib's strict pin: `configurations.configureEach { resolutionStrategy.eachDependency { if
   (it.requested.group == 'org.firstinspires.ftc') it.useVersion '12.0.0' } }`. This is a forced binary upgrade, which
   is what it is: the libraries were compiled against 11.1.0 and run on 12.0.0 classes. It is not a statement that
   SolversLib supports SDK 12.
4. Prepare that folder with the importer and run the Hornet tests on the package
   (`-Pftcsim.hornet=<that folder> -Pftcsim.hornetPackage=<a new folder>`).

Result: the importer builds it (Gradle 9.1.0 and AGP 8.13.2 with the project's own wrapper, on a JDK 21), the package
holds the SDK 12.0.0 jars, the host lists the same 12 OpModes, every one initialises, starts and stops, and the autonomous
routines score what they score on 11.1.0 (Close Auto 15, Far Auto 9, on both alliances). Nothing in FTC-Sim had to change
for it. It was run once, by hand; the steps above are the procedure.

## When a program fails on one SDK only

The simulator reports what the SDK reports. Start from the problem list of the session and the worker's log
(`%LOCALAPPDATA%\FTC-Sim\logs`): a class the shim cannot load, a call into Android that the shim does not have, or a
device the profile does not wire is named there with the member and where it was called from.
