# Testing

What is tested where, how to run it, and how to write a test that earns its place. At the last full verification
(2026-11-16, on a Ryzen 9 9950X) `gradlew check` with the Hornet properties given ran 1,790 tests with no failures and none
skipped; without them 19 are skipped because they need Hornet (18 in `acceptance-tests`, 1 in `project-import`). The
counts change with every test added or removed: run the build for today's.

## The layers

| Layer | Where | Proves | Needs |
|---|---|---|---|
| Unit and module tests | every module's `src\test` | One module's behaviour on its own: the kernel, the hub emulation and the device models at register level, traction, each game's scoring, the rewriter on probe classes, the wire format, the importer on fake projects, the window on a fake host (kept in `desktop-ui`'s test fixtures), the application's log and self test | nothing |
| Acceptance suite | `acceptance-tests` | The real FTC SDK in the robot world, under the kernel, on simulated hardware: OpModes, hardware lookups, drivers, sessions, matches, the worker process | nothing (the SDK comes from Maven) |
| Hornet | `acceptance-tests`, `project-import` | An unmodified real team program (Pedro Pathing, SolversLib, Panels, Limelight) on both fields; `RealHostProjectTest` the team's workflow on a copy of the checkout (the robot file written, edits rebuilt, old programs pruned, restarts on fresh workers) | `-Pftcsim.hornetPackage=<folder>`; the first time, and for `RealHostProjectTest`, also `-Pftcsim.hornet=<checkout>` and an Android SDK |
| SDK matrix | `acceptance-tests` with `-PftcSdk=` | The same suite on every release of `sdk-versions.md` | nothing |
| Distribution | `app` (`Distribution*Test`, `ThirdPartyNoticesTest`) | The built folder and zip with its jlink runtime, the real `FTC-Sim.cmd` on its own runtime (self test, options, exit code, a Java crash report), the notices | a built distribution (Gradle builds it) |
| Pictures | `desktop-ui` (`PracticeSmokeTest`), `acceptance-tests` (`RealFieldViewportTest`, `ViewportShots`) | The screens and the real fields look right: PNGs under `build\screens` | an OpenGL 3.3 context, else skipped |

`gradlew check` runs every layer except Hornet and the other releases of the SDK matrix (it runs the default, 11.1.0). The
Hornet tests are **skipped, not failed**, without their properties: read the skipped count when you claim something
about Hornet. The window is tested with synthetic events only, with bounded waits on state (`architecture/ui.md`).

## The acceptance suite

- **Scenarios are robot code.** Fixture OpModes live in `acceptance-tests\src\scenarios\java\dev\ftcsim\scenarios`; they
  are compiled against the SDK and run inside the robot class loader next to it, exactly as a team's OpModes do. The
  host-side tests (`src\test\java\dev\ftcsim\acceptance`) start them and look only at plain results they return.
  `src\hookScenarios` holds library hooks for the lifecycle tests, apart because the SDK runs every hook it finds by
  scanning the program.
- **Harness.** `TestRobot.builder()` builds a robot (a chassis, plants on a Control Hub, the Pinpoint, the colour sensor,
  the IMU or the Limelight) and `VirtualSession` runs OpModes on it in virtual time (`HardwareHostTest` shows the
  pattern); whole sessions go through `SimulationSession` (`SessionDriveTest`, `MatchSessionTest`).
- **A test class gets a JVM of its own** (`forkEvery = 1`, 2 GB): the SDK keeps state in singletons and library threads
  outlive a session.
- **Slicing.** A good session test runs the same scenario with different slicings of simulated time and asserts the same
  readings or an outcome within a tolerance (a time, a pose). That is how a hole in virtual time shows up. It does not
  compare world hashes: runs are not promised to repeat bit for bit (`decisions.md`, section 9). The kernel tests
  (`sim-core`) and `ForeignThreadHubTest` and `UnscheduledBusTest` (a library's own thread talks to the hubs while the
  robot runs and moves no simulated time) cover the same ground below the SDK; the rule tests of `robot-runtime` scan the
  JDK so that a new member that reads the clock or reaches the network cannot go unnoticed.
- **Per SDK.** `-PftcSdk=<version>` changes only the SDK jars the robot world loads. A new feature that the SDK sees (a
  device, a hook) must pass on every version of the matrix before it is done.
- **Two processes** (`@Tag("two-process")`): `WorkerProcessTest`, `RealHostSessionTest` and `RealHostHornetTest` start
  the real worker through the real launcher and assert that no process is left behind; `DistributionEndToEndTest` starts
  the distribution through `FTC-Sim.cmd`. `-Pftcsim.runtime=<runtime folder>` runs the test JVMs (and so the workers) on a
  Java runtime such as `app\build\dist\FTC-Sim\runtime`; use it with `--tests "*RealHost*Hornet*"`, because other tests
  there need a JDK's compiler or tools.
- `ProjectMappingsAcceptanceTest` holds the mapping the application stores against the fixed robot and real sessions, and
  runs without the gated checkout. `HornetMapping` is the Hornet mapping that the tests opening a session share.

### The Hornet tests (gated)

| Test | What it proves |
|---|---|
| `HornetBringupTest` | Hornet's 12 OpModes initialise, start and stop on DECODE; Pedro's own tuners measure the simulated drivetrain within 1.4 % of the real robot's constants, and the same velocity for different slicings; library class-loading noise is one line, not thousands |
| `HornetAutoRunTest` | Close Auto and Far Auto, both alliances, played as timed matches from Hornet's start poses, score what they scored in development (15 and 9 artifacts) |
| `HornetTeleopTest` | A synthetic gamepad drives the robot across the field and the left trigger aligns and shoots what it carries |
| `HornetBiobuzzTest` | All 12 OpModes run on BIOBUZZ with a silent simulator (Hornet cannot score there: its camera map, intake and aim are DECODE's) |
| `HornetNetworkTest` | Panels' ports are simulated; no real socket is open during a run |
| `HornetPackageTest` (`acceptance-tests`), `HornetImportTest` (`project-import`) | The unmodified checkout imports, the original is hashed before and after, the package opens and the SDK host lists its OpModes |
| `RealHostHornetTest` | A session on Hornet in the real worker; the preparer builds nothing twice |

Hornet is `Overture-7421/FTC-Hornet-2026`; everything here was developed and checked against commit `5bdc809`
(2026-11-11, "Merge pull request #5 from Overture-7421/SolverLib"). `robots/hornet-fit.md` says how its drivetrain
was fitted. `HornetImportTest` needs only the checkout: `.\gradlew.bat :project-import:test -Pftcsim.hornet=<checkout>`.
A rehearsal on the SDK 12 template is in `sdk-versions.md`.

## Tests with a clock in them

A few tests assert on real time: `SessionSpeedTest` (Hornet's Init must run well over real time; it fails when
`SimulationSession.LOOP_NANOS` is made too small), `WindowsWaitableTimerTest` and the pacing tests, the worker's timeouts.
Their margins are wide. If one fails on a slow or busy machine, find out why before loosening anything; if you add one,
state what it guards in its first comment.

Some tests sleep in real time on purpose, to show that nothing happens: `SimulatedListenersTest` (an accept on a closed
listener must not spin or end), `RealtimeRunnerTest` (a paused run must not advance), `WorkerProcessTest` (a killed worker
is reported once) and `RobotUnwindTest` (an abandoned thread stays parked). A sleep there is the absence of an event, so
there is nothing to wait for instead.

## Writing a test

1. **It must be able to fail.** After it passes, break the code it guards (flip a sign, drop a line) and watch it fail,
   then restore the code.
2. **Test behaviour you add or fix**, a few focused cases and their edges, not accessors, constants or the framework.
3. **Put it where the behaviour lives**, in an existing test class when one fits. A device model is tested at register
   level in `sim-hardware` and through the real SDK driver in `acceptance-tests` (`adding-a-device.md`).
4. **Waiting.** No `Thread.sleep` to wait for something (bounded waits on state, simulated time), and no wall-clock
   assertions except in the tests above; assert outcomes (scores, poses within a tolerance, durations) rather than hashes
   of a whole world; seeds are explicit.
5. **Keep the machine clean.** Use a temporary folder; never write into the real `%LOCALAPPDATA%\FTC-Sim`; never open a real
   network port; never start the real window or use the real mouse, keyboard or focus.
6. **A skip must say how to enable the test** in its message, as the Hornet fixture does.
