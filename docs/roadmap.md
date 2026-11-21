# Known limits and next work

What FTC-Sim does not do yet and where to begin; for the product owner and anyone picking the next task. This page is the map: each limit is detailed in the page that owns it. Everything here is *possible* work, not decided work.

## Left out on purpose

Do not build these unless the product owner asks ([decisions](architecture/decisions.md)): recording and replaying runs; robot description files and a robot editor (the two robots are built in); air drag; a Rust or other native sidecar; foul and ranking-point rules; an opposing or second robot; macOS or Linux; features for other seasons; mechanisms that push on the chassis.

## Cannot run yet

| Capability | Today | Where to begin |
|---|---|---|
| Webcams and `VisionPortal` | No camera frames: the lookup is refused by name and listed as unsupported. The Limelight 3A works (HTTP) | [limelight](devices/limelight.md). `hardware.camera` (`TagSight`, `PoseSolver`, `CameraRack`) already turns the world into tag detections; a webcam needs its own wire and the SDK camera API in `sdk-common` |
| Other sensors: external IMU, a second Pinpoint or colour sensor, REV 2 m distance sensor | Unsupported by decision: the lookup fails with a message, the device is unbound, the session lists a problem. (The OctoQuad is supported, on a robot whose turret has encoders.) | [adding-a-device](architecture/adding-a-device.md); a distance sensor reads `DeviceContext.rays()` |
| The Control Hub's newer BHI260AP IMU | A BNO055 model stands in, so `IMU` interface users cannot tell; `BHI260IMU` itself is refused | [bno055-imu](devices/bno055-imu.md) |
| Motors beyond the goBILDA Yellow Jacket and the SWYFT Spike | Only `MotorCatalog` entries can be used, and only by the two profiles | Add entries (REV HD Hex and Core Hex are the usual ones) and name them in `RobotProfiles`: [fixed-robots](robots/fixed-robots.md) |
| Servos and pins that do something | Servos accept commands with no effect, unmapped pins read idle, `hardwareMap.get(LED.class, ...)` is not made | [fixed-robots](robots/fixed-robots.md) |

Per-device limits are in the "not modelled" part of each `devices\*.md` page; a field's are in [decode](fields/decode.md) and [biobuzz](fields/biobuzz.md). Hornet cannot score on BIOBUZZ (its camera map, intake and aim are DECODE's), so BIOBUZZ has only stand-in programs: the red and blue driver TeleOps that tip a HIVE and a diagnostic stub ([biobuzz-stub](robots/biobuzz-stub.md)). The chassis constants are fitted to Hornet ([hornet-fit](robots/hornet-fit.md)) and are Hornet's robot's.

## Robot world: still open

Explained in [decisions](architecture/decisions.md).

- **Library timers and timed waits are not on the simulated clock** (`Timer`, `Semaphore.tryAcquire`, `BlockingQueue.poll`, `Condition.await`, `LockSupport.parkNanos`, `Thread.join` called by library code): they run in real time beside the simulation, which does not wait for them. The table-driven rewriter makes this incremental (`package-info` of `dev.ftcsim.runtime`).
- `Runtime.availableProcessors`, the time zone and the locale are the machine's.
- Threads that swallow an interrupt stay running when a session closes (Hornet leaves three); only a host running several sessions in one JVM carries them.

- **Panels dashboard:** only ports 8001 (page) and 8002 (websocket) listen for real, on `127.0.0.1`; a dashboard on any other port, a program on another address and every other listener stay simulated. If another program (a second FTC-Sim, a real Panels) holds a port the page cannot be opened and the session lists a problem. The page's own traffic over the websocket was not watched in a browser: the HTTP API and the websocket handshake are tested ([testing](testing.md)).

## Window, importing, distribution

- **Window:** the robot is its chassis box (no wheels, no mechanisms), a timed match has no countdown or buzzer, and controller hot-plug is untested on hardware. The rest is listed in [ui](architecture/ui.md).
- **Importing:** OpModes that code registers (as Panels does) cannot be listed before the program runs; a project's own `packagingOptions` are not read ([prepared-program](architecture/prepared-program.md)). Pruning of open programs and the memory the worker keeps per robot world are in [app](architecture/app.md).
- **Distribution:** no installer, signature or automatic update; how SmartScreen and antivirus treat the unpacked `runtime\java.exe` is unmeasured ([distribution](architecture/distribution.md)).
- **Licence:** FTC-Sim's own code has **none yet**; the product owner must choose one. The distribution carries the third-party notices.

## Not measured yet

Needs machines this project did not have: a student laptop with four cores and an integrated GPU; a display above 60 Hz ([viewport-measurements](architecture/viewport-measurements.md) holds what one desktop gave); a clean Windows PC with no Java and a school or company policy; Explorer's double-click behaviour; controller hot-plug.

## Suggested order

For the stated goals (testing robot code, practising driving):

1. Run the unmeasured checks on a real student laptop: they decide whether the 144 Hz and start-up promises hold.
2. Sensors and motors teams use that the catalogue lacks: the REV 2 m distance sensor, REV motors.
3. Webcam and `VisionPortal` support, since many teams use AprilTags that way.
4. Library timers on the simulated clock, so a team's `Timer` runs in simulated time like the rest of its code.
5. Draw the wheels and mechanisms in the window.
