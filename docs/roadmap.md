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
| Webcams and `VisionPortal` | No camera frames; the lookup is refused by name and the session lists it as unsupported. The Limelight 3A works (it speaks HTTP) | `docs/devices/limelight.md` and the `hardware.camera` package (`TagSight`, `PoseSolver`, `CameraRack`) already turn the world into tag detections; a webcam is not an HTTP device, so it needs a wire of its own and the SDK's camera API in `sdk-common` |
| Other sensors: an OctoQuad, an external IMU, a second Pinpoint or colour sensor, the REV 2 m distance sensor | Unsupported by decision: the lookup fails with a message, the device is not bound and the session lists it among its problems. The robot's sensors are the fixed ideal ones | `architecture/adding-a-device.md`; a distance sensor reads `DeviceContext.rays()` |
| The Control Hub's newer BHI260AP IMU | A BNO055 model stands in, so programs that ask for the `IMU` interface cannot tell; `BHI260IMU` itself is refused with a message | `docs/devices/bno055-imu.md` ("The newer Control Hub") |
| Motors beyond the goBILDA Yellow Jacket family | Only `MotorCatalog` entries can be named | Add catalogue entries (REV HD Hex and Core Hex are the usual ones), `robots/fixed-robots.md` |
| Servos and digital pins that do something | Servos accept commands with no effect and unmapped pins read idle (high, or 0 V); nothing in the world reads a digital output; `hardwareMap.get(LED.class, ...)` is not made | `robots/fixed-robots.md` ("Servos and pins") |
| Other I2C features | Per-device limits are in each device page: `devices/*.md` ("Not modelled", "Not simulated", "Known gaps") | the page of the device |

## The robot world: what is still open

Each is explained in `architecture/decisions.md` section 10.

- **Library timers and timed waits are not on the simulated clock** (`Timer`, `Semaphore.tryAcquire`,
  `BlockingQueue.poll`, `Condition.await`, `LockSupport.parkNanos`, `Thread.join` called by library code): they run in real
  time beside the simulation, which does not wait for them. The table-driven rewriter makes this incremental: package-info
  of `dev.ftcsim.runtime`.
- `Runtime.availableProcessors`, the time zone and the locale are the machine's. Threads that swallow an interrupt are left
  running when a session closes (Hornet leaves three); the worker is a process that ends, so only a host that runs several
  sessions in one JVM carries them.

## Physics, fields and mechanisms

- Chassis constants are fitted to Hornet (`robots/hornet-profile.md`) and are the fixed robot's for every team; only mass and
  wheel diameter come from the mapping.
- The fields' own lists of gaps are in `fields/decode.md` and `fields/biobuzz.md` ("Known gaps"): rigid balls can stack
  in DECODE's goal now and then, no fouls, ranking points, opposing robot or opposing human player, the robot's outline for
  LEAVE, BASE and PARK is a rectangle (default 18 in), BIOBUZZ's manual leaves two scoring questions open.
- Hornet cannot score on BIOBUZZ: its camera map, intake and aim are DECODE's. That is the robot code, not the simulator. It
  also means BIOBUZZ's turret has no real program to check it with; only a fixture OpMode does (`robots/fixed-robots.md`).

## The window

All in `architecture/ui.md` ("Deliberately not done yet"). The ones that matter most to a driver:

- The robot is its chassis box in the simulator's viewport; no wheels, and nothing for a mechanism that rises above the box (a piece carried up
  Hornet's conveyor is drawn above it). Drawing the design's wheels and mechanisms would fix this.
- No countdown, buzzer or sound in a timed match; no rumble or LED on controllers; controller hot-plug and GUID matching are
  untested on hardware.
- Start poses are matched to alliances by the name beginning with RED or BLUE (`StartPoses`); a structured alliance on
  `StartPose` (a protocol change, see `architecture/link.md`) would remove the convention. The progress line of a build says
    what it is doing and not how far along it is.
- Walls are drawn opaque; Top and Driver views guess which way the driver stands.
- Nothing watches the project's files: the code is picked up when the person presses Restart code (a deliberate choice).
  The Robot screen maps the motors of a team's code onto the fixed robot of the game and sets its mass, wheel diameter, hub
  mounting and Limelight mount (`robots/fixed-robots.md`); the robot's size and mechanisms are the design's and are not edited.
  The worker keeps part of every robot world it held, found by measurement and avoided by restarting on a fresh worker,
  not fixed (`architecture/app.md`).

## Importing and distribution

- OpModes that code registers (as Panels does) cannot be listed before the program runs, and a project's own
  `packagingOptions` are not read (`architecture/prepared-program.md`, "Not done").
- A program that a session still has open is pruned only by a later build (`architecture/app.md`).
- No installer, signature or automatic update; a zip from the internet carries the Mark of the Web
  (`architecture/distribution.md`, "What is not in it"). The Java runtime is bundled, so there is no first-run download; what
  is unmeasured is how Windows SmartScreen and antivirus programs treat the unpacked `runtime\java.exe`
  (`architecture/distribution.md`, "Not verified"). Decision 7 holds the choice of modules in the runtime; `jdk.localedata`
  (+10 MB) is the one a team in another locale might want.
- FTC-Sim's own code has **no licence yet**; the product owner has to choose one. The distribution already carries the
  third-party notices.

## Not measured yet (needs machines this project did not have)

A student laptop with four cores and an integrated GPU, and a display above 60 Hz (`architecture/viewport-measurements.md`
holds what was measured, on one desktop); a clean Windows PC with no Java and a school or company policy
(`architecture/distribution.md`, "Not verified"); Explorer's double-click behaviour; controller hot-plug.

## If someone asks what to do next

A suggestion in order of value for the stated goals, testing robot code and practising driving:

1. Run the unmeasured checks above on a real student laptop; they decide whether the 144 Hz and start-up promises hold.
2. The sensors and motors that teams use and the catalogue lacks: the REV 2 m distance sensor, REV motors.
3. Webcam and `VisionPortal` support, since many teams use AprilTags that way.
4. Library timers on the simulated clock, so that a team's `Timer` runs in simulated time like the rest of its code.
5. Draw the wheels and mechanisms in the window.
