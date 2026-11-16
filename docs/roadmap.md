# Known limits and next work

What FTC-Sim does not do yet, why, and where to begin. The detail of each limit is in the page of its area (links
below); this page is the map. What is here is *possible* work, not decided work: the product owner chooses what is wanted.

## Left out on purpose

Do not build these unless the product owner asks (`architecture/decisions.md`, `AGENTS.md`): recording and replaying
runs; TOML or YAML robot profiles; air drag; a Rust or other native sidecar; foul and ranking-point rules; an opposing
robot or a second robot; a browser dashboard for Panels; macOS or Linux; features for other seasons; mechanisms that push
on the chassis.

## What the simulator cannot run yet

| Capability | Today | Where to begin |
|---|---|---|
| Webcams and `VisionPortal` | No camera frames; the lookup is refused by name and the session lists it as unsupported. The Limelight 3A works (it speaks HTTP) | `docs/devices/limelight.md`; `hardware.camera` (`TagSight`, `PoseSolver`, `CameraRack`) already turns the world into tag detections; a webcam needs a wire of its own and the SDK's camera API in `sdk-common` |
| Other sensors: an OctoQuad, an external IMU, a second Pinpoint or colour sensor, the REV 2 m distance sensor | Unsupported by decision: the lookup fails with a message, the device is not bound and the session lists it among its problems | `architecture/adding-a-device.md`; a distance sensor reads `DeviceContext.rays()` |
| The Control Hub's newer BHI260AP IMU | A BNO055 model stands in, so programs that ask for the `IMU` interface cannot tell; `BHI260IMU` itself is refused | `docs/devices/bno055-imu.md` ("The newer Control Hub") |
| Motors beyond the goBILDA Yellow Jacket family | Only `MotorCatalog` entries can be named | Add catalogue entries (REV HD Hex and Core Hex are the usual ones), `robots/fixed-robots.md` |
| Servos and digital pins that do something | Servos accept commands with no effect and unmapped pins read idle; `hardwareMap.get(LED.class, ...)` is not made | `robots/fixed-robots.md` ("Servos and pins") |

Per-device limits are in the "Not modelled" or "Not simulated" sections of `devices/*.md`; a field's are in "Known gaps" of
`fields/decode.md` and `fields/biobuzz.md`. Hornet cannot score on BIOBUZZ (its camera map, intake and aim are DECODE's), so
BIOBUZZ's turret has no real program to check it with, only a fixture OpMode. The chassis constants are fitted to Hornet
(`robots/hornet-profile.md`) and are the fixed robot's for every team.

## The robot world: what is still open

Each is explained in `architecture/decisions.md` section 10.

- **Library timers and timed waits are not on the simulated clock** (`Timer`, `Semaphore.tryAcquire`,
  `BlockingQueue.poll`, `Condition.await`, `LockSupport.parkNanos`, `Thread.join` called by library code): they run in real
  time beside the simulation, which does not wait for them. The table-driven rewriter makes this incremental (package-info
  of `dev.ftcsim.runtime`).
- `Runtime.availableProcessors`, the time zone and the locale are the machine's. Threads that swallow an interrupt are left
  running when a session closes (Hornet leaves three); only a host that runs several sessions in one JVM carries them.

## The window, importing and distribution

- The window: the ones that matter most to a driver are that the robot is its chassis box (no wheels, no mechanisms), a
  timed match has no countdown or buzzer, and controller hot-plug is untested on hardware; `architecture/ui.md`
  ("Deliberately not done yet") has the rest.
- Importing: OpModes that code registers (as Panels does) cannot be listed before the program runs, and a project's own
  `packagingOptions` are not read (`architecture/prepared-program.md`); `architecture/app.md` ("Not done") has the
  pruning of open programs and the memory the worker keeps per robot world.
- Distribution: no installer, signature or automatic update, and how SmartScreen and antivirus programs treat the unpacked
  `runtime\java.exe` is unmeasured (`architecture/distribution.md`, "What is not in it" and "Not verified").
- FTC-Sim's own code has **no licence yet**; the product owner has to choose one. The distribution carries the third-party
  notices.

## Not measured yet (needs machines this project did not have)

A student laptop with four cores and an integrated GPU, and a display above 60 Hz (`architecture/viewport-measurements.md`
holds what was measured, on one desktop); a clean Windows PC with no Java and a school or company policy; Explorer's
double-click behaviour; controller hot-plug.

## If someone asks what to do next

A suggestion in order of value for the stated goals, testing robot code and practising driving:

1. Run the unmeasured checks above on a real student laptop; they decide whether the 144 Hz and start-up promises hold.
2. The sensors and motors that teams use and the catalogue lacks: the REV 2 m distance sensor, REV motors.
3. Webcam and `VisionPortal` support, since many teams use AprilTags that way.
4. Library timers on the simulated clock, so that a team's `Timer` runs in simulated time like the rest of its code.
5. Draw the wheels and mechanisms in the window.
