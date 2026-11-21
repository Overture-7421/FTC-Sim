# How FTC-Sim fits together

Read this before touching code: it shows the processes, class loaders, threads and modules, and where to look for what ([decisions.md](decisions.md) gives the reasons).

## The runtime in one picture

```text
UI process                                  Worker process (one per link; ends when the link closes)
----------                                  --------------------------------------------------------
desktop-ui  window, input, 3D viewport      worker      serves the link, opens sessions
app         composition root                simulator   SimulationSession, RealtimeRunner, mechanisms
sim-link    RemoteSimulatorLink, wire  <==== loopback TCP, a token per launch ====>
                                              |- sim-core       kernel: the only mover of time
                                              |- sim-physics, games (field framework, two games)
                                              |- sim-hardware   emulated REV hubs, devices, cameras, pins
                                              '- robot world, its own class loader ("robot"):
                                                   team program and libraries, FTC SDK,
                                                   android-shim, sdk-common
```

- The UI knows the simulation only through `SimulatorLink` (`dev.ftcsim.contracts.session`): commands in, immutable snapshots and status out ([link.md](link.md)). A crashing robot or a native physics fault cannot take the window down; a new worker is the cleanest reset.
- In the worker, the **host** (the application's class loader) holds kernel, physics, hardware emulation, fields, session and link. The **robot world** (`RobotClassLoader`, child-first, every class rewritten at load) holds the team's program, the real FTC SDK, `android-shim` and `sdk-common`.
- Only `dev.ftcsim.contracts.*` is shared: no SDK type reaches the host, and nothing of the host but `contracts` reaches the robot. The SDK keeps state in singletons, so one JVM runs one robot world at a time.

## Time and threads

Simulated time moves only on the **simulator thread**: the `RealtimeRunner` thread in the worker, the test's own thread in tests. `RealtimeRunner` is the one place wall-clock time touches the simulation, and it only decides *when* simulated time moves, never what happens in it.

At every instant the kernel does the same things in the same order: world steps that fall due, scheduled callbacks, then the **robot thread**. That thread runs only while it holds the baton (`RobotGate`) and hands it back at its next synchronisation point (a clock read past the horizon, a sleep, a yield, a monitor wait, a loop back edge). Time then jumps to the earliest of the next world step, the next callback, the robot's wake-up and the caller's target.

| Thread | Rule |
|---|---|
| Simulator | The only one that moves simulated time. Not thread-safe by design: `SimulationSession` is called from it only. |
| Robot | Runs the OpMode between the simulator's moves. One robot thread is supported; a second is a recorded problem. |
| Foreign (a library's timer, Panels' ticker) | Runs in real time, is not scheduled, and the kernel does not wait for it. Its hub traffic goes through `transmitUnscheduled`, which moves no simulated time. |
| Link and UI | Post commands and read the latest immutable status and snapshot; nothing the UI does can hold the simulation up. |

Rates: physics 240 Hz (step *n* at `floor(n * 1e9 / 240)` ns); hubs and the plants wired to them 1 kHz; one SDK event-loop pass every 5 ms. Robot code pays a clock read 200 ns, a yield 1 µs, hub traffic what its wire takes, a backward jump 100 ns (`SimulationSession.LOOP_NANOS`; why: [decisions.md](decisions.md) section 2).

## What is real, replaced, emulated and simulated

| | What | Where |
|---|---|---|
| **Real** | The FTC SDK (`OpModeManagerImpl`, `LynxModule`, motor controllers, I2C drivers, `HardwareMap`), the team's code and libraries | robot world |
| **Replaced** | The Android framework (a clean-room subset that fails loudly), the SDK's USB device, the network (except Panels' ports 8001 and 8002, real on the loopback address only: [decisions](decisions.md) section 10), clocks, sleeps, waits, native library loading, `System.exit` | `android-shim`, `sdk-common`, `robot-runtime` rewrites, `contracts.hooks` |
| **Emulated** | REV hub firmware speaking the real RHSP protocol; the fixed ideal sensors (Pinpoint, colour sensor, the Control Hub's BNO055, Limelight 3A); idle digital and analog pins | `sim-hardware` |
| **Simulated** | Rigid bodies with mecanum wheel traction; mechanisms (plants, intakes, storages, launchers); the two fields and their rules | `sim-physics`, `simulator` (`mechanisms`), `games` |

`SimLynxUsbDevice` hands the SDK's RHSP datagrams to a `HubWire` (`LynxBus`) and the emulated hubs answer, so a new device is a model on a hub's bus, never new SDK code ([adding-a-device.md](adding-a-device.md)).

## Life of a run

1. **Open.** The window builds a `SessionRequest` from the `Catalogue` the worker sent at the handshake. `Open` goes over the link to `WorkerServer`, `LiveSessions` and `SessionFactory`, which turn the names into a `SessionConfig`.
2. **Build.** `RealtimeRunner.open` starts the runner thread, which builds the `SimulationSession`: a `PhysicsWorld`, the field in it, a `RobotRig` (hubs on a bus, the chassis, a plant on every mapped motor port, the fixed sensors) and, through `RobotWorldLauncher`, the robot world, where `SdkRobotHostFactory` boots the SDK. `simulator` reaches `sdk-common` by class name only, never at compile time.
3. **Init, Start, Stop.** `DriverStation` presses the buttons at the simulated instant the caller is at (`TimedMatch`: at exact simulated instants); the SDK's own `OpModeManagerImpl` runs the state machine.
4. **Running.** `RealtimeRunner` asks the kernel to advance. `hardwareMap.get(...)` makes a device on first use (`SimHardwareMap`) from the SDK's own driver class, which talks to the hubs in RHSP datagrams. Motors drive plants, plants drive the physics world, devices read it through a `DeviceContext`, and the field applies its rules after every physics step.
5. **Showing it.** After each slice the runner publishes a `WorldSnapshot` and a `SessionStatus`; the UI draws the latest at up to 144 Hz and never changes the simulation.

## The modules

The dependency rules are code: the `moduleRules` block of the root `build.gradle.kts`; an unlisted dependency fails `check` (`checkModuleRules`). Each package's `package-info.java` says what it is for.

| Module | May depend on | Owns (where to start) |
|---|---|---|
| `sim-contracts` | nothing | Vocabulary shared by both class loaders and processes: `Hooks`, `SimulatorLink`, `HubWire`, `MotorPlant`, `WorldSnapshot` |
| `platform` | nothing | Windows high-resolution timer, frame limiter: `FrameLimiter`, `WindowsWaitableTimer`, `HighResolutionParker` |
| `sim-core` | contracts | Virtual-time kernel: `SimulationKernel`, `FixedRateWorld`, `RobotGate`, `KernelDiagnostics` |
| `robot-runtime` | contracts | Robot class loader and load-time rewriting: `RobotClassLoader`, `HookRewriter`, the `*Rules` tables |
| `android-shim` | nothing | The audited subset of `android.*` and `dalvik.system.DexFile` the SDK touches |
| `sdk-common` | contracts, android-shim | The SDK host (OpMode manager, event loop, lifecycle hooks, hardware map): `SdkRobotHost`, `SdkBootstrap`, `SimHardwareMap`, `SimLynxUsbDevice` |
| `sim-hardware` | contracts | Hub firmware (`LynxBus`, `LynxHub`), I2C peripherals, sensor models (`DeviceModels`), camera (`CameraModels`), `WiringTable` |
| `sim-physics` | contracts | Jolt via jolt-jni: `PhysicsWorld`, `MecanumChassis`, pieces, pivots (`PivotSpec`), native DLL (`NativeLibrary`) |
| `games` | contracts, physics | Field framework (`dev.ftcsim.field`: `Field`, `FieldRun`, `EntityIds`; nothing game-specific) and the two fields in separate packages that do not refer to each other (`GamePackagesTest`): `DecodeField`, `BiobuzzField` |
| `simulator` | contracts, core, physics, hardware, games, robot-runtime, platform | A session: `SimulationSession`, `RobotRig`, `RobotWorldLauncher`, the built-in robots (`RobotProfiles`, `RobotMapping`, `RobotDesign`, `RobotSpec`), `RealtimeRunner`, the mechanisms (`dev.ftcsim.mechanisms`) |
| `sim-link` | contracts | The UI's side of the process boundary and the wire format: `RemoteSimulatorLink`, `WorkerLauncher`, package `link.wire` |
| `worker` | contracts, link, simulator, games, project-import | The process that runs a session and serves the link: `WorkerMain`, `WorkerServer`, `SessionFactory` |
| `project-import` | contracts | Turns an unmodified team project into a prepared program: `ProjectImporter` |
| `desktop-ui` | contracts, platform | Material 3 window, 3D viewport, input: `AppHost`, `ProjectRunner`, Compose screens, `ui.render` |
| `app` | contracts, link, desktop-ui, project-import (and the worker's and robot world's jars as files) | Composition root: `Main`, `RealHost`, `ImporterPreparer`, `AppFolders`, `SelfTest`; `FTC-Sim.cmd` and the jlink runtime |
| `acceptance-tests` | everything | End-to-end tests with the real SDK, Hornet and the real worker ([../testing.md](../testing.md)) |

- `desktop-ui` does not know `sim-link`, the worker or the importer: it declares the seam (`AppHost`, `ProgramPreparer`) and `app` implements it.
- `sim-hardware`, `sim-physics`, `games` and the mechanisms know nothing of the FTC SDK; `sdk-common` knows nothing of physics or the UI. The meeting places are `contracts` and `simulator`.
- `sim-hardware` never reads a clock: time arrives through `advanceTo` and the bus's clock supplier.

## Units, frames and ids

- Metres, kilograms, seconds, radians inside the simulator; inches only where outside documents use them (Hornet's Pedro Pathing poses, the fields' CAD). Simulated time is `long` nanoseconds.
- **Field frame:** origin mid-field on the tile top, Z up, +X toward the audience, +Y toward the blue wall (red wall on the audience's left); heading counter-clockwise from +X (`docs/fields/`).
- **Robot frame:** X forward, Y left, Z up, origin at the chassis centre ([../robots/fixed-robots.md](../robots/fixed-robots.md)).
- **Entity ids:** chassis is 1; a field's bodies start at 1000 (`EntityIds`); a field's own body kinds are 100 or more (`BodyKinds`). A snapshot orders bodies by id.
- **Motors:** positive hub voltage makes the plant's encoder count rise; the SDK is told to invert nothing. Mounting differences (mirrored motors) live in the robot profiles, never in device code.

## Where to look when ...

| Symptom | Look in |
|---|---|
| Robot code throws on an Android call, or a library cannot load a class | `android-shim`; the worker's log; [decisions.md](decisions.md) section 10 (the APK index) |
| A device reads wrongly, or its SDK driver fails | the `sim-hardware` model of that device; the SDK driver is the specification |
| Two runs differ | not promised to repeat ([decisions.md](decisions.md) section 9); library threads run in real time (section 10); `KernelDiagnostics.problems()` |
| The robot drives or shoots wrongly | the robot profiles and design ([../robots/fixed-robots.md](../robots/fixed-robots.md)), `MecanumChassis`, the mechanisms |
| A score is wrong | `games`, `docs/fields/` |
| The window misbehaves | `desktop-ui`, [ui.md](ui.md) |
| Importing a team project fails | `project-import`, [prepared-program.md](prepared-program.md), the Projects screen's build view and log |
| `FTC-Sim.cmd` does not start | `app\src\dist\FTC-Sim.cmd`, [distribution.md](distribution.md), `%LOCALAPPDATA%\FTC-Sim\logs` |
| The worker dies or the link stalls | `sim-link`, `worker`, [link.md](link.md) |

## Glossary

- **Catalogue:** what a `SessionRequest` may name (fields, start poses, human actions).
- **Profile / design:** a profile (`RobotMapping`, held by `RobotProfiles`) is the data of one of the two built-in robots, Hornet's and BIOBUZZ's; the design (`RobotDesign`) turns it into the `RobotSpec` the simulator builds from ([../robots/fixed-robots.md](../robots/fixed-robots.md)).
- **Piece:** a game element (ARTIFACT, POLLEN, NECTAR) with exactly one owner: free body, carried by a storage, or in a reserve.
- **Problem:** a condition the session reports (a robot thread that would not end, a declined network request, a runaway thread).
- **RHSP:** the REV Hub Serial Protocol the SDK speaks to its hubs.
- **Slicing:** how a caller cuts simulated time into advances; what an OpMode sees does not depend on it.
