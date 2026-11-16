# How FTC-Sim fits together

One page to read before touching anything. `decisions.md` says why each choice was made; this page says what exists and
how the parts meet. Every class and module named here is real: search for it.

## The runtime in one picture

```text
UI process                                  Worker process (one per link; it ends when the link closes)
----------                                  ------------------------------------------------------
desktop-ui  window, input, 3D viewport      worker      serves the link, opens sessions
app         composition root                simulator   SimulationSession and RealtimeRunner
sim-link    RemoteSimulatorLink, wire  <==== loopback TCP, a token per launch ====>
                                              |- sim-core       the kernel: the only thing that moves time
                                              |- sim-physics, games (the field framework and the two games)
                                              |- simulator also holds the mechanisms: plants, intakes, launchers
                                              |- sim-hardware   emulated REV hubs, devices, cameras, pins
                                              '- the robot world, a class loader of its own ("robot"):
                                                   the team's program, the FTC SDK, its libraries,
                                                   android-shim and sdk-common
```

The UI knows the simulation only through `SimulatorLink` (package `dev.ftcsim.contracts.session`): commands in,
immutable snapshots and status out (`link.md`). A crashing robot program, a runaway loop or a native fault in the
physics engine therefore cannot take the window down, and a new worker is the cleanest reset.

Inside the worker there are two worlds that share one package, `dev.ftcsim.contracts.*`:

| World | Class loader | Holds |
|---|---|---|
| Host | the application's | kernel, physics, hardware emulation, fields, session, link |
| Robot world | `RobotClassLoader` ("robot"), child-first, every class rewritten at load time | the team's program and libraries, the real FTC SDK, `android-shim`, `sdk-common` |

No SDK type ever reaches the host, and nothing of the host but `contracts` reaches the robot. The FTC SDK keeps state in
singletons, so one JVM runs one robot world at a time.

## Time and threads

Simulated time moves only on the **simulator thread**: in the worker that is the `RealtimeRunner` thread (in tests,
the test's own thread). `RealtimeRunner` is the one place wall-clock time touches the simulation, and it only decides
*when* simulated time moves, never what happens in it.

At every instant the kernel does the same things in the same order: world steps that fall due, scheduled callbacks,
then the **robot thread**, which runs only while it holds the baton (`RobotGate`) and hands it back at its next
synchronisation point (a clock read past the horizon, a sleep, a yield, a monitor wait, a loop back edge). Time then
jumps to the earliest of the next world step, the next callback, the robot's wake-up and the caller's target.

| Thread | Rule |
|---|---|
| Simulator thread | The only one that moves simulated time. Not thread-safe by design: `SimulationSession` is called from it only. |
| Robot thread | Runs the OpMode between the simulator's moves. One robot thread is supported; a second is a recorded problem. |
| Foreign threads (a library's timer, Panels' ticker) | Run in real time and are not scheduled; the kernel does not wait for them. Their hub traffic goes through `transmitUnscheduled`, which moves no simulated time. |
| Link and UI threads | Post commands and read the latest immutable status and snapshot; nothing the UI does can hold the simulation up. |

What robot code pays in simulated time: a clock read 200 ns, a yield 1 µs, hub traffic what its wire takes, and every
backward jump of its code `SimulationSession.LOOP_NANOS` = 100 ns, about one trivial loop iteration on a Control Hub.
The rates of the world: physics 240 Hz (step *n* at `floor(n * 1e9 / 240)` ns), the hubs and the plants wired to them
1 kHz, one SDK event-loop pass every 5 ms. `decisions.md` section 2 explains why these numbers matter.

## What is real, replaced, emulated and simulated

| | What | Where |
|---|---|---|
| **Real** | The FTC SDK (`OpModeManagerImpl`, `LynxModule`, motor controllers, I2C drivers, `HardwareMap`), the team's code and libraries | the robot world |
| **Replaced** | The Android framework (a clean-room subset that fails loudly), the SDK's USB device, the network, clocks, sleeps, waits, native library loading, `System.exit` | `android-shim`, `sdk-common`, `robot-runtime` rewrites + `contracts.hooks` |
| **Emulated** | REV hub firmware speaking the real RHSP protocol, the fixed ideal sensors (Pinpoint, colour sensor, the Control Hub's BNO055, the Limelight 3A), idle digital and analog pins | `sim-hardware` |
| **Simulated** | Rigid bodies with mecanum wheel traction, mechanisms (plants, intakes, storages, launchers), the two fields and their rules | `sim-physics`, `simulator` (`mechanisms`), `games` |

The SDK's own classes run unchanged. Only the USB device is replaced: `SimLynxUsbDevice` hands the SDK's serialised
RHSP datagrams to a `HubWire` (`LynxBus`), and the emulated hubs answer. A new device is therefore a model on a hub's
bus, never new SDK code (`adding-a-device.md`).

## Life of a run

1. **Setup.** The window builds a `SessionRequest` (program, field, alliance, robot mapping, seed, start pose, speed)
   from the `Catalogue` the worker sent at the handshake, and the link sends `Open`.
2. **Open.** `WorkerServer` hands the request to its `SessionOpener` (`LiveSessions`). `SessionFactory` turns the names
   into a `SessionConfig`: the robot (`RobotDesign.forField(field).spec(mapping)`), the prepared program (`ProgramResolver`,
   `RobotProgram`), the field (`FieldRegistry`) and the start pose. `RealtimeRunner.open` starts the runner thread, which
   builds the `SimulationSession`: a `PhysicsWorld`, the field built in it, a `RobotRig` (hubs on a bus, the chassis, a
   plant on every motor port the mapping wires, the fixed sensors, the Limelight) and, through `RobotWorldLauncher`, the robot
   world, in which `SdkRobotHostFactory` boots the SDK (`SdkBootstrap`, `SdkRobotHost`). The simulator module reaches
   `sdk-common` only by that class name, never at compile time.
3. **Init, Start, Stop.** `DriverStation` presses the buttons at the simulated instant the caller is at; the SDK's own
   `OpModeManagerImpl` runs the state machine. Gamepad states arrive with `SetGamepad` (both gamepads always exist).
   A timed match (`TimedMatch`) is the same buttons scheduled at exact simulated instants.
4. **Running.** `RealtimeRunner` asks the kernel to advance. Robot code calls `hardwareMap.get(...)`: `SimHardwareMap`
   creates the device the first time (lazy discovery) from the SDK's own driver classes, and everything the driver does
   goes to the hubs as RHSP datagrams. Motors drive plants, plants drive the physics world, devices read it through a
   `DeviceContext`, and the field applies its rules after every physics step.
5. **Showing it.** After each slice the runner publishes a `WorldSnapshot` and a `SessionStatus` (simulated time, speed,
   telemetry, score, problems, hardware); the UI draws the latest at up to 144 Hz. Drawing never changes the simulation.

## The modules

The dependency rules are code: the `moduleRules` block of the root `build.gradle.kts`. A dependency that is not listed
fails `check` (`checkModuleRules`).

| Module | May depend on | Owns | Start reading |
|---|---|---|---|
| `sim-contracts` | nothing | The vocabulary shared by both class loaders and both processes | `contracts.hooks.Hooks`, `contracts.session.SimulatorLink`, `contracts.hardware.HubWire` and `MotorPlant`, `contracts.field.FieldDescription`, `contracts.snapshot.WorldSnapshot` |
| `platform` | nothing | The Windows high-resolution timer and the frame limiter | `FrameLimiter`, `WindowsWaitableTimer` |
| `sim-core` | contracts | The virtual-time kernel | `SimulationKernel`, `FixedRateWorld`, `KernelConfig`, `KernelDiagnostics` (package-info of `core.kernel`) |
| `robot-runtime` | contracts | The robot class loader and the load-time rewriting | `RobotClassLoader`, `HookRewriter`, the `*Rules` tables |
| `android-shim` | nothing | The audited subset of `android.*` and `dalvik.system.DexFile` that the SDK touches | package-info of `android` |
| `sdk-common` | contracts, android-shim | The SDK host: OpMode manager, event loop, lifecycle hooks, the hardware map and its providers | `SdkRobotHost`, `SdkBootstrap`, `sdk.hardware.SimHardwareMap`, `SimLynxUsbDevice` |
| `sim-hardware` | contracts | Hub firmware (`hardware.lynx`), I2C peripherals, the sensor models, the camera, the wiring table | `LynxBus`, `LynxHub`, `DeviceModels`, `CameraModels`, `WiringTable` |
| `sim-physics` | contracts | Jolt through jolt-jni: world, mecanum chassis, pieces, pivots (the games' gate and hives), the pinned DLL | `PhysicsWorld`, `MecanumChassis`, `PieceSpec`, `PivotSpec`, `NativeLibrary` |
| `games` | contracts, physics | What a game field has to provide (package `dev.ftcsim.field`, nothing about any game) and the two fields, one package each that do not refer to each other (`GamePackagesTest`): geometry, rules, scoring | `Field`, `FieldRun`, `FieldSetup`, `EntityIds`; `DecodeField`/`DecodeRun` (`game.decode`), `BiobuzzField`/`BiobuzzRun` (`game.biobuzz`) |
| `simulator` | contracts, core, physics, hardware, games, robot-runtime, platform | A session: the fixed robot of the game, rig, kernel, physics, field, robot controller; the real-time runner; the mechanisms (package `dev.ftcsim.mechanisms`: plants, storages, intakes, launchers with their turret and the conditions that activate them) | `SimulationSession`, `RobotRig`, `RobotWorldLauncher`, `RealtimeRunner`, `robot.RobotDesign`, `robot.RobotSpec`, `Mechanisms`, `ShaftPlant`, `StorageSpec`, `LaunchSpec` |
| `sim-link` | contracts | The UI's side of the process boundary and the wire format | `RemoteSimulatorLink`, `WorkerLauncher`, package `link.wire` |
| `worker` | contracts, link, simulator, games, project-import | The process that runs a session and serves the link | `WorkerMain`, `WorkerServer`, `SessionFactory` |
| `project-import` | contracts | Turns an unmodified team project into a prepared program | `ProjectImporter` (package-info of `importer`) |
| `desktop-ui` | contracts, platform | The Material 3 window, the 3D viewport, input mapping | `ui.host.AppHost`, `ProjectRunner` (open and restart a project), `ProjectsScreen`, `PracticeScreen` and `MappingScreen` (Kotlin Compose), `ui.render` |
| `app` | contracts, link, desktop-ui, project-import (and the worker's and the robot world's jars as files) | The composition root: where things are on the computer; `FTC-Sim.cmd`, `runtime-options` and the jlink runtime of the distribution (`src\dist`, `runtime.gradle.kts`) | `Main`, `RealHost`, `ImporterPreparer`, `ProjectMappingsImpl`, `AppFolders`, `AppLog`, `SelfTest`, `RuntimePin` |
| `acceptance-tests` | everything | End-to-end tests with the real SDK, Hornet and the real worker | `docs/testing.md` |

A few things the table hides:

- `desktop-ui` does not know `sim-link`, the worker or the importer. It declares the seam (`AppHost`,
  `ProgramPreparer`); `app` implements it.
- `sim-hardware`, `sim-physics`, `games` and the mechanisms in `simulator` know nothing of the FTC SDK (`simulator` reaches it only by class name at run time), and `sdk-common` knows
  nothing of physics or the UI. The only meeting place is `contracts` and `simulator`.
- `sim-hardware` never reads a clock: time arrives through `advanceTo` and the bus's clock supplier.

## Units, frames and ids

- Metres, kilograms, seconds and radians inside the simulator; inches only where outside documents use them (Hornet's
  Pedro Pathing poses, the fields' CAD). Simulated time is `long` nanoseconds.
- **Field frame**: origin at the middle of the field on the tile top, Z up, +X towards the audience, +Y towards the blue
  wall (the red wall is on the audience's left); heading counter-clockwise from +X. Both fields use it
  (`docs/fields/`).
- **Robot frame**: X forward, Y left, Z up, origin at the centre of the chassis (`docs/robots/fixed-robots.md`).
- **Entity ids**: the chassis is 1, a field's bodies start at 1000 (`EntityIds`); a body's kind is 100 or more for a
  field's own kinds (`BodyKinds`). A snapshot orders bodies by id, so equal worlds give equal snapshots.
- **Motors**: positive hub voltage makes the plant's encoder count rise; the SDK is told to invert nothing, and mounting
  differences (mirrored motors) live in the robot mapping, never in device code.

## Where to look when ...

| Symptom | Look in |
|---|---|
| Robot code throws on an Android call, or a library cannot load a class | `android-shim`; the worker's log; `docs/architecture/decisions.md` section 10 (the APK index) |
| A device reads wrongly, or the SDK driver for it fails | `sim-hardware` model of that device; the SDK driver is the specification |
| Two runs give different results | Runs are not promised to repeat (`decisions.md` section 9); library threads run in real time beside the simulation (section 10); `KernelDiagnostics.problems()` |
| The robot drives or shoots wrongly | the robot's design and mapping (`docs/robots/fixed-robots.md`), `sim-physics` (`MecanumChassis`), the mechanisms (`simulator`, package `dev.ftcsim.mechanisms`) |
| A score is wrong | `games` (`game.decode` or `game.biobuzz`) and its page in `docs/fields/` |
| The window misbehaves | `desktop-ui` and `docs/architecture/ui.md` |
| Importing a team project fails | `project-import`, `docs/architecture/prepared-program.md`, the Projects screen's build view and log |
| `FTC-Sim.cmd` does not start | `app\src\dist\FTC-Sim.cmd`, `docs/architecture/distribution.md`, `%LOCALAPPDATA%\FTC-Sim\logs` |
| The worker dies or the link stalls | `sim-link`, `worker`, `docs/architecture/link.md` |

## Glossary

- **Back edge**: a backward jump in robot code, charged 100 ns of simulated time so that a loop that only waits lets time pass.
- **Baton**: the turn-taking between the simulator thread and the robot thread (`RobotGate`).
- **Catalogue**: what a `SessionRequest` may name (fields, start poses, human actions, the motors a mapping may name).
- **Foreign thread**: a thread of the robot world that the kernel does not schedule; it runs in real time and is not waited for.
- **Hook**: a static method that rewritten robot bytecode calls instead of a JDK member (`Hooks`, `TimeHooks`, ...).
- **Host / robot world**: the two class loaders of the worker.
- **Piece**: a game element (ARTIFACT, POLLEN, NECTAR) with exactly one owner: free body, carried by a storage, or in a reserve.
- **Plant**: the mechanical model on a hub motor port (`MotorPlant`, `ShaftPlant`).
- **Prepared program**: what the importer makes of a team project: a manifest, the class path, resources; no build tool needed to open it.
- **Problem**: a condition the session reports (a robot thread that would not end or came back from a wait the simulator does not control, a declined network request, a runaway thread).
- **Mapping**: what a student says about their robot, `RobotMapping`; the **design** (`RobotDesign`) is the fixed robot of a game (`docs/robots/fixed-robots.md`).
- **RHSP**: the REV Hub Serial Protocol the SDK speaks to its hubs.
- **Slicing**: how a caller cuts simulated time into advances; what an OpMode sees does not depend on it.
- **Zone**: a region of a mechanism (intake, storage, launcher) whose activation is a condition on named motors' shaft speeds.
