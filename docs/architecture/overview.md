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
                                              |- sim-physics, sim-mechanisms, sim-field, game-*
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
| Foreign threads (a library's timer, Panels' ticker) | Run in real time, are not scheduled, and must never change what the robot sees (`WorldThreads`, `transmitUnscheduled`). |
| Link and UI threads | Post commands and read the latest immutable status and snapshot; nothing the UI does can hold the simulation up. |

What robot code pays in simulated time: a clock read 200 ns, a yield 1 µs, hub traffic what its wire takes, and every
backward jump of its code `SimulationSession.LOOP_NANOS` = 100 ns, about one trivial loop iteration on a Control Hub.
The rates of the world: physics 240 Hz (step *n* at `floor(n * 1e9 / 240)` ns), the hubs and the plants wired to them
1 kHz, one SDK event-loop pass every 5 ms. `decisions.md` section 2 explains why these numbers matter.

## What is real, replaced, emulated and simulated

| | What | Where |
|---|---|---|
| **Real** | The FTC SDK (`OpModeManagerImpl`, `LynxModule`, motor controllers, I2C drivers, `HardwareMap`), the team's code and libraries | the robot world |
| **Replaced** | The Android framework (a clean-room subset that fails loudly), the SDK's USB device, the network, clocks, sleeps, waits, randomness, native library loading, `System.exit` | `android-shim`, `sdk-common`, `robot-runtime` rewrites + `contracts.hooks` |
| **Emulated** | REV hub firmware speaking the real RHSP protocol, I2C devices (Pinpoint, colour sensor, OctoQuad, BNO055), digital and analog pins, the Limelight 3A | `sim-hardware` |
| **Simulated** | Rigid bodies with mecanum wheel traction, mechanisms (plants, intakes, storages, launchers), the two fields and their rules | `sim-physics`, `sim-mechanisms`, `sim-field`, `game-*` |

The SDK's own classes run unchanged. Only the USB device is replaced: `SimLynxUsbDevice` hands the SDK's serialised
RHSP datagrams to a `HubWire` (`LynxBus`), and the emulated hubs answer. A new device is therefore a model on a hub's
bus, never new SDK code (`adding-a-device.md`).

## Life of a run

1. **Setup.** The window builds a `SessionRequest` (program, field, alliance, robot profile, seed, start pose, speed)
   from the `Catalogue` the worker sent at the handshake, and the link sends `Open`.
2. **Open.** `WorkerServer` hands the request to its `SessionOpener` (`LiveSessions`). `SessionFactory` turns the names
   into a `SessionConfig`: the robot profile (`RobotProfileLoader`), the prepared program (`ProgramResolver`,
   `RobotProgram`), the field (`FieldRegistry`) and the start pose. `RealtimeRunner.open` starts the runner thread, which
   builds the `SimulationSession`: a `PhysicsWorld`, the field built in it, a `RobotRig` (hubs on a bus, the chassis, a
   plant on every motor port the profile wires, devices, cameras, pins) and, through `RobotWorldLauncher`, the robot
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
| `sim-hardware` | contracts | Hub firmware (`hardware.lynx`), I2C peripherals, device models, cameras, pin sources | `LynxBus`, `LynxHub`, `DeviceModels`, `CameraModels` |
| `sim-physics` | contracts | Jolt through jolt-jni: world, mecanum chassis, pieces, hives, gates, the pinned DLL | `PhysicsWorld`, `MecanumChassis`, `PieceSpec`, `NativeLibrary` |
| `sim-mechanisms` | contracts, physics | Plants, storages, intakes, launchers and the conditions that activate them | `Mechanisms`, `ShaftPlant`, `StorageSpec`, `LaunchSpec` |
| `sim-field` | contracts, physics | What a game field has to provide, and nothing about any game | `Field`, `FieldRun`, `FieldSetup`, `EntityIds` |
| `game-decode`, `game-biobuzz` | contracts, physics, field | The two fields: geometry, rules, scoring | `DecodeField`/`DecodeRun`, `BiobuzzField`/`BiobuzzRun` |
| `simulator` | contracts, core, physics, hardware, mechanisms, field, both games, robot-runtime, platform | A session: profile, rig, kernel, physics, field, robot controller; the real-time runner | `SimulationSession`, `RobotRig`, `RobotWorldLauncher`, `RealtimeRunner`, `profile.RobotProfile` |
| `sim-link` | contracts | The UI's side of the process boundary and the wire format | `RemoteSimulatorLink`, `WorkerLauncher`, package `link.wire` |
| `worker` | contracts, link, simulator, games, project-import | The process that runs a session and serves the link | `WorkerMain`, `WorkerServer`, `SessionFactory` |
| `project-import` | contracts | Turns an unmodified team project into a prepared program | `ProjectImporter` (package-info of `importer`) |
| `desktop-ui` | contracts, platform | The Material 3 window, the 3D viewport, input mapping | `ui.host.AppHost`, `PracticeScreen` and `SetupScreen` (Kotlin Compose), `ui.render` |
| `app` | contracts, link, desktop-ui, project-import (and the worker's and the robot world's jars as files) | The composition root: where things are on the computer | `Main`, `RealHost`, `ImporterPreparer`, `AppFolders`, `SelfTest` |
| `launcher` | nothing | The start-up scripts that find or fetch the pinned JDK | `FTC-Sim.cmd`, `FTC-Sim.ps1`, `jdk-manifest.json` |
| `acceptance-tests` | everything | End-to-end tests with the real SDK, Hornet and the real worker | `docs/testing.md` |

A few things the table hides:

- `desktop-ui` does not know `sim-link`, the worker or the importer. It declares the seam (`AppHost`,
  `ProgramPreparer`); `app` implements it.
- `sim-hardware`, `sim-physics`, `sim-mechanisms` and `sim-field` know nothing of the FTC SDK, and `sdk-common` knows
  nothing of physics or the UI. The only meeting place is `contracts` and `simulator`.
- `sim-hardware` never reads a clock: time arrives through `advanceTo` and the bus's clock supplier.

## Units, frames and ids

- Metres, kilograms, seconds and radians inside the simulator; inches only where outside documents use them (Hornet's
  Pedro Pathing poses, the fields' CAD). Simulated time is `long` nanoseconds.
- **Field frame**: origin at the middle of the field on the tile top, Z up, +X towards the audience, +Y towards the blue
  wall (the red wall is on the audience's left); heading counter-clockwise from +X. Both fields use it
  (`docs/fields/`).
- **Robot frame**: X forward, Y left, Z up, origin at the centre of the chassis (`docs/robots/robot-profile.md`).
- **Entity ids**: the chassis is 1, a field's bodies start at 1000 (`EntityIds`); a body's kind is 100 or more for a
  field's own kinds (`BodyKinds`). A snapshot orders bodies by id, so equal worlds give equal snapshots.
- **Motors**: positive hub voltage makes the plant's encoder count rise; the SDK is told to invert nothing, and mounting
  differences (mirrored motors) live in the robot profile, never in device code.

## Where to look when ...

| Symptom | Look in |
|---|---|
| Robot code throws on an Android call, or a library cannot load a class | `android-shim`; the worker's log; `docs/architecture/decisions.md` section 10 (the APK index) |
| A device reads wrongly, or the SDK driver for it fails | `sim-hardware` model of that device; the SDK driver is the specification |
| The same run gives different results | `decisions.md` sections 2, 9 and 10; `KernelDiagnostics.problems()`; `docs/development.md`, determinism rules |
| The robot drives or shoots wrongly | the robot profile (`docs/robots/`), `sim-physics` (`MecanumChassis`), `sim-mechanisms` |
| A score is wrong | `game-decode` or `game-biobuzz` and its page in `docs/fields/` |
| The window misbehaves | `desktop-ui` and `docs/architecture/ui.md` |
| Importing a team project fails | `project-import`, `docs/architecture/prepared-program.md`, the Prepare screen's build log |
| `FTC-Sim.cmd` does not start | `launcher`, `docs/architecture/distribution.md`, `%LOCALAPPDATA%\FTC-Sim\logs` |
| The worker dies or the link stalls | `sim-link`, `worker`, `docs/architecture/link.md` |

## Glossary

- **Back edge**: a backward jump in robot code, charged 100 ns of simulated time so that a loop that only waits lets time pass.
- **Baton**: the turn-taking between the simulator thread and the robot thread (`RobotGate`).
- **Catalogue**: what a `SessionRequest` may name (fields, start poses, human actions, robot profiles).
- **Foreign thread**: a thread of the robot world that the kernel does not schedule.
- **Golden hash**: the committed state hashes of a scripted physics scenario, which a build must reproduce bit for bit.
- **Hook**: a static method that rewritten robot bytecode calls instead of a JDK member (`Hooks`, `TimeHooks`, ...).
- **Host / robot world**: the two class loaders of the worker.
- **Piece**: a game element (ARTIFACT, POLLEN, NECTAR) with exactly one owner: free body, carried by a storage, or in a reserve.
- **Plant**: the mechanical model on a hub motor port (`MotorPlant`, `ShaftPlant`).
- **Prepared program**: what the importer makes of a team project: a manifest, the class path, resources; no build tool needed to open it.
- **Problem**: a condition the session reports because it weakens the run (an unreproducible hub write, a declined network request, a runaway thread).
- **Profile**: the JSON description of the robot outside its code (`docs/robots/robot-profile.md`).
- **RHSP**: the REV Hub Serial Protocol the SDK speaks to its hubs.
- **Slicing**: how a caller cuts simulated time into advances; a run must not depend on it.
- **Zone**: a region of a mechanism (intake, storage, launcher) whose activation is a condition on named motors' shaft speeds.
