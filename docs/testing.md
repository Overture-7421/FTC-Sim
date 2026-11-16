# Testing

What is tested where, how to run it, and how to write a test that earns its place. At the last full verification
(2026-11-15, after the sensors became fixed and ideal and the turret was added) `gradlew check` ran 1,927 tests with no
failures; 21 were skipped because they need Hornet (20 in `acceptance-tests`, 1 in `project-import`). The Hornet tests passed
when last given it (2026-11-14), before this change, and have to be run again.

## The layers

| Layer | Where | Proves | Needs |
|---|---|---|---|
| Unit and module tests | every module's `src\test` | One module's behaviour on its own: the kernel (`sim-core`), the hub emulation and the device models at register level (`sim-hardware`), traction (`sim-physics`), each game's scoring (`games`), the rewriter on probe classes (`robot-runtime`), the wire format (`sim-link`), the importer on fake projects (`project-import`), the window on a fake host (`desktop-ui`, which keeps the fake host in its test fixtures), the application's log and self test (`app`) | nothing |
| Acceptance suite | `acceptance-tests` | The real FTC SDK in the robot world, under the kernel, on simulated hardware: OpModes, hardware lookups, device drivers, sessions, matches, the worker process | nothing (the SDK comes from Maven) |
| Hornet | `acceptance-tests`, `project-import` | An unmodified real team program (Pedro Pathing, SolversLib, Panels, Limelight) on both fields; `RealHostProjectTest` the team's workflow on a copy of the checkout (the robot file written, edits rebuilt, old programs pruned, restarts on fresh workers, and the measurement of why) | `-Pftcsim.hornetPackage=<folder>`; the first time, and for `RealHostProjectTest`, also `-Pftcsim.hornet=<checkout>` and an Android SDK |
| SDK matrix | `acceptance-tests` with `-PftcSdk=` | The same suite on 11.0.0, 11.1.0 (the default), 11.2.0, 11.2.1 and 12.0.0 | nothing |
| Distribution | `app` (`Distribution*Test`, `ThirdPartyNoticesTest`) | The built folder and zip with its jlink runtime, the real `FTC-Sim.cmd` on its own runtime (self test, hand-off of options, arguments and exit code, a Java crash report), the notices | a built distribution (Gradle builds it) |
| Pictures | `desktop-ui` (`PracticeSmokeTest`), `acceptance-tests` (`RealFieldViewportTest`, `ViewportShots`) | The screens and the real fields look right: PNGs written under `build\screens` | an OpenGL 3.3 context, else skipped |

`gradlew check` runs every layer except Hornet and the other releases of the SDK matrix (it runs the default, 11.1.0). The
Hornet tests are **skipped, not failed**, without their properties: read the skipped count when you claim something
about Hornet.

## The acceptance suite

- **Scenarios are robot code.** Fixture OpModes live in `acceptance-tests\src\scenarios\java\dev\ftcsim\scenarios`; they
  are compiled against the SDK and run inside the robot class loader next to it, exactly as a team's OpModes do. The
  host-side tests (`src\test\java\dev\ftcsim\acceptance`) start them and look only at plain results they return.
  `src\hookScenarios` holds library hooks for the lifecycle tests, apart because the SDK finds hooks by scanning the
  program and runs every one it finds.
- **Harness.** `TestRobot.builder()` builds a robot (a chassis, plants on a Control Hub, the Pinpoint, the colour sensor, the IMU or
  the Limelight) and `VirtualSession` runs OpModes on it in virtual time; `HardwareHostTest` shows the pattern. Whole
  sessions go through `SimulationSession` (`SessionDriveTest`, `MechanismsSessionTest` with the turret fixture, `MatchSessionTest`,
  `DevicesSessionTest` with the sensors-by-class rules, `ImuSessionTest`, `PinsSessionTest` for unmapped pins).
- **A test class gets a JVM of its own** (`forkEvery = 1`, 2 GB): the SDK keeps state in singletons and library threads
  outlive a session.
- **Slicing.** A good session test runs the same scenario with different slicings of simulated time and asserts the same
  readings or an outcome within a tolerance (a time, a pose). That is how a hole in virtual time shows up. It does not
  compare world hashes: there are none, and runs are not promised to repeat bit for bit (`decisions.md`, section 9).
- **Per SDK.** `-PftcSdk=<version>` changes only the SDK jars the robot world loads. A new feature that the SDK sees (a
  device, a hook) must pass on every version of the matrix before it is done.
- **Two processes** (`@Tag("two-process")`): `WorkerProcessTest`, `RealHostSessionTest` and `RealHostHornetTest` start
  the real worker through the real launcher, and assert that no process is left behind. `DistributionEndToEndTest` starts
  the real distribution through `FTC-Sim.cmd` on its own runtime. `-Pftcsim.runtime=<runtime folder>` runs the test JVMs
  of `acceptance-tests` (and so the workers the real-host tests start) on a Java runtime, for example the distribution's
  `app\build\dist\FTC-Sim\runtime`; it is meant for `--tests "*RealHost*Hornet*"`, because other tests there need a JDK's
  compiler or tools.

Acceptance tests in `src\test\java\dev\ftcsim\acceptance` are named for what they prove: `ProjectMappingsAcceptanceTest` (the mapping
the application stores, held against the fixed robot and real sessions: the Hornet mapping round-trips and opens, every catalogue
motor on the wheels and the mechanisms, a BIOBUZZ turret, a schema 1 file refused and kept) runs without the gated checkout. `HornetMapping` is the
Hornet mapping that the tests that open a session through a request share.

### The Hornet tests (gated)

| Test | What it proves |
|---|---|
| `HornetBringupTest` | Hornet's 12 OpModes initialise, start and stop on DECODE; Pedro's own tuners measure the simulated drivetrain within 1.4 % of the real robot's constants; the same tuner measures the same velocity, within a tolerance, for different slicings; library class-loading noise is one line, not thousands |
| `HornetAutoRunTest` | Close Auto and Far Auto, both alliances, played as timed matches from Hornet's own start poses, score what they scored in development (15 and 9 artifacts) |
| `HornetTeleopTest` | A synthetic gamepad drives the robot across the field and the left trigger aligns and shoots what it carries |
| `HornetBiobuzzTest` | All 12 OpModes run on BIOBUZZ with a silent simulator (Hornet cannot score there: its camera map, intake and aim are DECODE's) |
| `HornetNetworkTest` | Panels' ports are simulated; no real socket is open during a run |
| `HornetPackageTest`, `HornetImportTest` | The unmodified checkout imports, the original is hashed before and after, the package opens and the SDK host lists its OpModes |
| `RealHostHornetTest` | A session on Hornet in the real worker; the preparer builds nothing twice |

Hornet is `Overture-7421/FTC-Hornet-2026`; everything here was developed and checked against commit `5bdc809`
(2026-11-11, "Merge pull request #5 from Overture-7421/SolverLib"). `robots/hornet-profile.md` says how its drivetrain
was fitted and how to reproduce it. `HornetImportTest` (in `project-import`) needs only the checkout:
`.\gradlew.bat :project-import:test -Pftcsim.hornet=<checkout>`. A rehearsal on the SDK 12 template is in `sdk-versions.md`.

## Virtual-time tests

- Kernel tests (`sim-core`): a robot's observations are the same however the host slices time and however busy the
  machine is; a robot thread that is blocked outside the simulator's control, waits for a lock or is interrupted is
  handled; runaway threads are unwound.
- `VirtualTimeHostTest`, the session tests and `HornetBringupTest`: an OpMode cut into different slices of simulated
  time sees the same times and readings, and ends where it ends within a tolerance.
- `ForeignThreadHubTest` (acceptance) and `UnscheduledBusTest` (`sim-hardware`): a library's own thread can talk to the
  hubs safely while the robot runs, and moves no simulated time.
- The rule tests of `robot-runtime` scan the JDK so that a new member that reads the clock or reaches the network cannot
  go unnoticed.

## Tests with a clock in them

A few tests assert on real time: `SessionSpeedTest` (Hornet's Init must run well over real time; it fails when the cost of
a loop iteration in `SimulationSession.LOOP_NANOS` is made too small), `WindowsWaitableTimerTest` and the pacing tests,
the worker's timeouts. Their margins are wide. If one fails on a slow or busy machine, find out why before loosening
anything; if you add one, state what it guards in its first comment.

## The window without a hand on the machine

`PracticeSmokeTest` runs the real application stack headless (the real screens drawn into memory by Compose's
`ImageComposeScene`, the real render thread on a hidden OpenGL context) and walks Projects (the build, the game question), the Robot page and the Simulator with
synthetic pointer and key events. `AppFlowTest` (open a project, restart, the notices, start-up on the last project),
`ProjectRunnerTest`, `BuildViewTest`, `MappingScreenTest`, `StartPanelTest` and the others drive screens with
synthetic events, with bounded waits on state and no sleeps. No test uses the real mouse, keyboard or focus, and none may.
`architecture/ui.md` ("Tests without a window or a hand on the machine") lists them.

## Writing a test

1. **It must be able to fail.** After it passes, break the code it guards (flip a sign, drop a line) and watch it fail,
   then restore the code. The timer test and the loop-cost test were checked this way.
2. **Test behaviour you add or fix**, a few focused cases and their edges, not accessors, constants or the framework.
3. **Put it where the behaviour lives**, in an existing test class when one fits. A device model is tested at register
   level in `sim-hardware` and through the real SDK driver in `acceptance-tests` (`adding-a-device.md`).
4. **Waiting.** No `Thread.sleep` to wait for something; no wall-clock assertions except in the tests above; assert
   outcomes (scores, poses within a tolerance, durations) rather than hashes of a whole world; seeds are explicit.
5. **Keep the machine clean.** Use a temporary folder; never write into the real `%LOCALAPPDATA%\FTC-Sim` (settings,
   programs, logs); never open a real network port; never start the real window.
6. **A skip must say how to enable the test** in its message, as the Hornet fixture does.
