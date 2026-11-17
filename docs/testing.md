# Testing

What is tested where, how to run it and how to write a test that earns its place; for anyone who changes or reviews code. Commands and hazards are in [development.md](development.md).

Last full run (2026-11-16): `gradlew check` with the Hornet properties ran 1,790 tests, none failed or skipped. Without them 19 are skipped (18 in `acceptance-tests`, 1 in `project-import`). Counts change with every test: run the build for today's.

## The layers

| Layer | Where | Proves | Needs |
|---|---|---|---|
| Unit and module | every module's `src\test` | One module alone: kernel, hub and device models at register level, traction, game scoring, the rewriter, the wire format, the importer on fake projects, the window on a fake host (in `desktop-ui`'s test fixtures), the app's log and self test | nothing |
| Acceptance | `acceptance-tests` | The real FTC SDK in the robot world on simulated hardware: OpModes, hardware lookups, drivers, sessions, matches, the worker process | nothing (the SDK comes from Maven) |
| Hornet | `acceptance-tests`, `project-import` | An unmodified real team program on both fields | `-Pftcsim.hornetPackage`; the first run and `RealHostProjectTest` also `-Pftcsim.hornet` and an Android SDK ([setup](development.md)) |
| SDK matrix | `acceptance-tests` with `-PftcSdk=` | The same suite on every release in [sdk-versions.md](sdk-versions.md) | nothing |
| Distribution | `app` (`Distribution*Test`, `ThirdPartyNoticesTest`) | The built folder and zip with its runtime, the real `FTC-Sim.cmd` (self test, options, exit code, a Java crash report), the notices | a built distribution (Gradle builds it) |
| Pictures | `desktop-ui` (`PracticeSmokeTest`), `acceptance-tests` (`RealFieldViewportTest`, `ViewportShots`) | The screens and fields look right: PNGs under `build\screens` | OpenGL 3.3, else skipped |

`gradlew check` runs every layer except Hornet and the SDK releases other than the default 11.1.0. Hornet tests are **skipped, not failed**, without their properties: read the skipped count before you claim anything about Hornet.

## The acceptance suite

- **Scenarios are robot code.** Fixture OpModes in `acceptance-tests\src\scenarios\java\dev\ftcsim\scenarios` compile against the SDK and run in the robot class loader, as a team's OpModes do. Host-side tests (`src\test\java\dev\ftcsim\acceptance`) start them and look only at the plain results they return. `src\hookScenarios` holds library hooks for the lifecycle tests, apart because the SDK runs every hook it finds.
- **Harness.** `TestRobot.builder()` builds a robot; `VirtualSession` runs OpModes on it in virtual time (`HardwareHostTest` shows the pattern); whole sessions use `SimulationSession` (`SessionDriveTest`, `MatchSessionTest`).
- **One JVM per test class** (`forkEvery = 1`, 2 GB): the SDK keeps singletons and library threads outlive a session.
- **Slicing.** A session test runs one scenario with different slicings of simulated time and asserts the same readings, or an outcome within a tolerance (a time, a pose). That is how a hole in virtual time shows. No world hashes: runs need not repeat bit for bit.
- **Below the SDK**, the `sim-core` kernel tests, `ForeignThreadHubTest` and `UnscheduledBusTest` (a library thread talks to the hubs and moves no simulated time) cover the same ground; the `robot-runtime` rule tests scan the JDK so a new member that reads the clock or reaches the network cannot go unnoticed.
- **Per SDK.** `-PftcSdk=<version>` changes only the SDK jars the robot world loads. A feature the SDK sees (a device, a hook) must pass on every release of the matrix before it is done.
- **Two processes** (`@Tag("two-process")`): `WorkerProcessTest`, `RealHostSessionTest`, `RealHostProjectTest`, `RealHostHornetTest` and `app`'s `RealHostWorkerTest` start the real worker through the real launcher and assert no process is left behind; `DistributionEndToEndTest` starts the distribution through `FTC-Sim.cmd`. `-Pftcsim.runtime=<runtime folder>` runs the test JVMs, and so the workers, on a Java runtime such as `app\build\dist\FTC-Sim\runtime`; use it with `--tests "*RealHost*Hornet*"`, because other tests need a JDK's compiler or tools.
- `ProjectMappingsAcceptanceTest` checks the stored mapping against the fixed robot and real sessions, with no checkout. `HornetMapping` is the Hornet mapping the session-opening tests share.
- `BiobuzzStubSessionTest` runs the whole BIOBUZZ robot (drive, Pinpoint, Limelight on the turret, servo turret, OctoQuad encoders) with the stub program ([biobuzz-stub](robots/biobuzz-stub.md)); it is also the end-to-end check of the servo, OctoQuad and camera-on-turret code. A turret test must compare while the turret is still: telemetry lags by its interval.

### The Hornet tests

Hornet is `Overture-7421/FTC-Hornet-2026` (Pedro Pathing, SolversLib, Panels, Limelight), checked at commit `5bdc809` (2026-11-11). [robots/hornet-fit.md](robots/hornet-fit.md) says how its drivetrain was fitted. Scores are minimums over a few attempts, not exact.

| Test | Proves |
|---|---|
| `HornetBringupTest` | All 12 OpModes initialise, start and stop on DECODE; Pedro's tuners measure the simulated drivetrain within 1.4 % of the real constants, and the same velocity for different slicings; class-loading noise is one line |
| `HornetAutoRunTest` | Close Auto and Far Auto, both alliances, as timed matches from Hornet's start poses, score what they did in development (15 and 9 artifacts) |
| `HornetTeleopTest` | A synthetic gamepad drives across the field; the left trigger aligns and shoots what it carries |
| `HornetBiobuzzTest` | All 12 OpModes run on BIOBUZZ with a silent simulator (Hornet cannot score there: its camera map, intake and aim are DECODE's) |
| `HornetNetworkTest` | Panels' ports are simulated; no real socket opens during a run |
| `HornetPackageTest` (`acceptance-tests`), `HornetImportTest` (`project-import`) | The unmodified checkout imports, the original is hashed before and after, the package opens, the SDK host lists its OpModes. `HornetImportTest` needs only `-Pftcsim.hornet` |
| `RealHostHornetTest` | A session on Hornet in the real worker; the preparer builds nothing twice |
| `RealHostProjectTest` | The team's workflow on a copy of the checkout: robot file written, edits rebuilt, old programs pruned, restarts on fresh workers |

A rehearsal on the SDK 12 template is in [sdk-versions.md](sdk-versions.md).

## Tests with a clock in them

- A few tests assert on real time: `SessionSpeedTest` (Hornet's Init must run well over real time; it fails when `SimulationSession.LOOP_NANOS` is too small), `WindowsWaitableTimerTest`, the pacing tests, the worker's timeouts. Their margins are wide. If one fails on a slow machine, find out why before loosening it. A new one says what it guards in its first comment.
- Some tests sleep on purpose, to show that nothing happens: `SimulatedListenersTest` (an accept on a closed listener must not spin or end), `RealtimeRunnerTest` (a paused run must not advance), `WorkerProcessTest` (a killed worker is reported once), `RobotUnwindTest` (an abandoned thread stays parked). There is no event to wait for.

## Writing a test

1. **It must be able to fail.** After it passes, break the code it guards (flip a sign, drop a line), watch it fail, restore.
2. **Test behaviour you add or fix**: a few focused cases and their edges, not accessors, constants or the framework.
3. **Put it where the behaviour lives**, in an existing class when one fits. A device model is tested at register level in `sim-hardware` and through the real SDK driver in `acceptance-tests` ([adding-a-device](architecture/adding-a-device.md)).
4. **No sleeps to wait for something**: wait on state with a bound, or on simulated time. No wall-clock assertions except in the tests above. Assert outcomes (scores, poses within a tolerance, durations), not hashes of a world. Seeds are explicit.
5. **Keep the machine clean.** Use a temporary folder; never write into the real `%LOCALAPPDATA%\FTC-Sim`; never open a real network port; never start the real window or use the real mouse, keyboard or focus. UI tests use synthetic events and bounded waits ([architecture/ui.md](architecture/ui.md)).
6. **A skip says how to enable the test** in its message, as the Hornet fixture does.
