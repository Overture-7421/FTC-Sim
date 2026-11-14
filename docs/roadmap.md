# Known limits and next work

What FTC-Sim does not do yet, why, and where to begin. The detail of each limit is in the page of its area (links
below); this page is the map. What is here is *possible* work, not decided work: the product owner chooses what is wanted.

## Left out on purpose

Do not build these unless the product owner asks (`architecture/decisions.md`, `development.md`):
recording and replaying runs; TOML or YAML robot profiles; air drag; a Rust or other native sidecar; foul and
ranking-point rules; an opposing robot or a second robot; a browser dashboard for Panels; macOS or Linux; features for
other seasons; mechanisms that push on the chassis.

## What the simulator cannot run yet

| Capability | Today | Where to begin |
|---|---|---|
| Webcams and `VisionPortal` | No camera frames; the lookup is refused by name and the session says so. The Limelight 3A works (it speaks HTTP) | `docs/devices/limelight.md` and the `hardware.camera` package (`TagSight`, `PoseSolver`, `CameraRack`) already turn the world into tag detections; a webcam is not an HTTP device, so it needs a wire of its own and the SDK's camera API in `sdk-common` |
| I2C sensors without a model, such as the REV 2 m distance sensor | The SDK driver finds an empty bus, as with an unplugged sensor | `architecture/adding-a-device.md`; a distance sensor reads `DeviceContext.rays()` |
| The Control Hub's newer BHI260AP IMU | A BNO055 model stands in, so programs that ask for the `IMU` interface cannot tell; `BHI260IMU` itself is refused with a message | `docs/devices/bno055-imu.md` ("The newer Control Hub") |
| Motors beyond the goBILDA Yellow Jacket family; motors defined inside a profile | Only `MotorCatalog` entries can be named | Add catalogue entries (REV HD Hex and Core Hex are the usual ones); a profile-defined motor needs a schema change (`RobotProfile`, the loader, `robots/robot-profile.md`) |
| Digital outputs and LEDs | Nothing in the world reads an output; `hardwareMap.get(LED.class, ...)` is not made. The SDK resets pins to input at OpMode init; the simulator does not | `docs/devices/digital-and-analog.md` ("Not simulated") |
| Other I2C features | Per-device limits are in each device page: `devices/*.md` ("Not modelled", "Not simulated", "Known gaps") | the page of the device |

## Determinism: what is still open

Each is explained in `architecture/decisions.md` sections 9 and 10.

- **Library timers and timed waits are not on the simulated clock** (`Timer`, `Semaphore.tryAcquire`, `BlockingQueue.poll`,
  `Condition.await`, `LockSupport.parkNanos`, `Thread.join` called by library code). A foreign thread that wakes while the
  robot thread runs is the residual risk (`KernelDiagnostics.worldWaits()`). The table-driven rewriter makes this
  incremental: package-info of `dev.ftcsim.runtime`.
- **Identity-hash iteration order** depends on the JVM's flags, so the repeatability claim is for one JVM build and one
  set of flags. No program has been seen to depend on it; Hornet's Far Auto gives the same world with and without the JIT.
- **The golden hashes have only run on the development machine.** Run `GoldenDeterminismTest` on an Intel CPU and on a second
  AMD CPU before claiming equality across machines.
- `Runtime.availableProcessors`, the time zone and the locale are the machine's. Threads that swallow an interrupt are left
  running when a session closes (Hornet leaves three); the worker is a process that ends, so only a host that runs several
  sessions in one JVM carries them.

## Physics, fields and mechanisms

- Chassis constants are fitted to Hornet (`robots/hornet-profile.md`); another robot gets the estimated defaults until
  someone fits it the same way.
- The fields' own lists of gaps are in `fields/decode.md` and `fields/biobuzz.md` ("Known gaps"): rigid balls can stack
  in DECODE's goal now and then, no fouls, ranking points, opposing robot or opposing human player, the robot's outline for
  LEAVE, BASE and PARK is a rectangle (default 18 in), BIOBUZZ's manual leaves two scoring questions open.
- Hornet cannot score on BIOBUZZ: its camera map, intake and aim are DECODE's. That is the robot code, not the simulator.

## The window

All in `architecture/ui.md` ("Deliberately not done yet"). The ones that matter most to a driver:

- The robot is its chassis box; no wheels, and nothing for a mechanism that rises above the box (a piece carried up
  Hornet's conveyor is drawn above it). A profile with visual parts would fix this.
- No countdown, buzzer or sound in a timed match; no rumble or LED on controllers; controller hot-plug and GUID matching are
  untested on hardware.
- Start poses are matched to alliances by the name beginning with RED or BLUE (`StartPoses`); a structured alliance on
  `StartPose` (a protocol change, see `architecture/link.md`) would remove the convention. The Prepare screen cannot show
  the labels of steps that have not begun.
- Walls are drawn opaque; Top and Driver views guess which way the driver stands.

## Importing and distribution

- OpModes that code registers (as Panels does) cannot be listed before the program runs, and a project's own
  `packagingOptions` are not read (`architecture/prepared-program.md`, "Not done").
- Prepared programs of earlier versions are not pruned (`architecture/app.md`).
- No installer, signature or automatic update; a zip from the internet carries the Mark of the Web
  (`architecture/distribution.md`, "What is not in it"). A jlinked runtime for PCs where PowerShell or the network is
  blocked is a build change away.
- FTC-Sim's own code has **no licence yet**; the product owner has to choose one. The distribution already carries the
  third-party notices.

## Not measured yet (needs machines this project did not have)

A student laptop with four cores and an integrated GPU, and a display above 60 Hz (`architecture/viewport-measurements.md`
holds what was measured, on one desktop); a clean Windows PC with no Java and a school or company policy
(`architecture/distribution.md`, "Not verified"); Explorer's double-click behaviour; controller hot-plug; the golden
hashes on other CPUs.

## If someone asks what to do next

A suggestion in order of value for the stated goals, testing robot code and practising driving:

1. Run the unmeasured checks above on a real student laptop; they decide whether the 144 Hz and start-up promises hold.
2. The sensors and motors that teams use and the catalogue lacks: the REV 2 m distance sensor, REV motors.
3. Webcam and `VisionPortal` support, since many teams use AprilTags that way.
4. Library timers on the simulated clock, which closes the one known gap in repeatability.
5. Draw the wheels and mechanisms in the window.
