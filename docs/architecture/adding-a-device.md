# Adding an ideal fixed device

The simulator runs the team's own SDK driver for a device, unchanged, against a model of the device. The driver talks I2C
to a hub's bus, the hub emulation (`sim-hardware`, `dev.ftcsim.hardware.lynx`) times and carries the transactions, and the
model answers them. The sensors of the robot are **fixed and ideal**: the robot has one of each kind, a model reports the
truth of the world with no settings a student could get wrong, and robot code finds it **by the driver class it asks
for, under any name**. So there is no per-project sensor description; adding a device is a model, a bus and a binding.

The devices that exist:

| Driver class(es) the code asks for | The sensor | Where | Page |
|---|---|---|---|
| `GoBildaPinpointDriver` | goBILDA Pinpoint, ideal (`GoBildaPinpointModel`) | Control Hub I2C bus 1 | `devices/pinpoint.md` |
| `ColorRangeSensor`, `NormalizedColorSensor`, `ColorSensor`, `RevColorSensorV3` | REV Color Sensor V3 (`RevColorSensorV3Model`), placed by the design | bus 2 | `devices/color-sensor.md` |
| `IMU`, `BNO055IMU`, `LynxEmbeddedBNO055IMUNew`, `LynxEmbeddedIMU` | The Control Hub's BNO055 (`Bno055Model`), mounted as `RobotMapping.hubLogo/hubUsb` say | bus 0 (inside the hub) | `devices/bno055-imu.md` |
| `Limelight3A` | The Limelight 3A (`LimelightModel`), mounted as `RobotMapping.camera` says | USB, over HTTP | `devices/limelight.md` |

Everything else is **unsupported** (an OctoQuad, an external IMU, a webcam, a second sensor of a kind, `BHI260IMU`): the
lookup fails with the SDK's own kind of message, the device is not bound, and the session lists it among its problems
(`HardwareDirectory.unsupported`, shown by `SimulationSession.problems()`). Servos and digital and analog pins are not
sensors and are not mapped: an unmapped servo accepts commands with no effect, and an unmapped pin reads idle (a digital
pin high through the hub's pull-up, an analog pin 0 V); the name gets a free port and is reported in `SessionStatus.unmapped`.

## 1. Write the model

A model is a class in `sim-hardware` that implements `I2cPeripheral` (or extends `RegisterFilePeripheral` for the common
pointer-and-auto-increment register map) and has a static factory `create(DeviceConfig config, DeviceContext context)`.

- **The SDK driver is the specification.** Read it for exactly which registers it writes and reads, in which order, in
  which units, and what it waits for.
- **Be ideal.** The model reports the truth of the world, and a setting the code writes that only describes the device's
  mounting or tuning (offsets, directions, scalars) is accepted and read back but has no effect, so that what the code
  programs is always right. Keep what the real device does that robot code can see: its boot and calibration times, its
  register quantisation, what it holds while it calibrates.
- **Settings** come from `config`. A design setting such as a place is read in the factory
  (`config.number("x")`); every getter names the device and the setting when it fails, and a setting the factory never asked
  for is rejected, so a typo fails loudly. A device with nothing to set reads nothing, and refuses any setting.
- **The world** is only what `DeviceContext` offers: `body()` is where the chassis really is (`BodyPose`), `rays()` is what a
  ray meets first (`SurfaceHit`), `nowNanos()` is the clock, and `onStep(listener)` registers work to do once per physics
  step (the host calls it in registration order after every 240 Hz step). The SDK polls inside a lock, where the world
  cannot advance, so while it polls the hub moves the time of its I2C transactions on by the time each poll takes;
  `nowNanos` can then be a few milliseconds later than the last physics step, and what a model reads from the world is as of
  that step. Keep timers on `nowNanos` and measurements on the step.
- **Threads.** A model is only called by the scheduled side and needs no locking of its own; it must not wait for another
  thread. See "Threads and the bus" in the documentation of `dev.ftcsim.hardware.lynx`.

## 2. Register it and give it a bus

One line in `DeviceModels.standard()` (the type id is a constant of `DeviceModels`), and a constant of
`HardwareDirectory.Sensor` that names the sensor and its bus on the parent hub. `RobotRig` attaches the model on that bus
with the settings `RobotDesign` gives it (`RobotSpec`).

## 3. Bind it by class

`I2cDriverProvider` (`sdk-common`) decides what a request is: `kindOf(class)` maps the driver classes and interfaces to the
sensor, `create` asks `HardwareDirectory.sensor(name, kind)` where it is (one name per sensor; a second name is refused and
recorded as unsupported) and builds the SDK's own driver on the SDK's I2C transport. A request for an interface the code
uses (`ColorRangeSensor`) names no driver, so the provider says which driver implements it. `WiringTable` (`sim-hardware`)
holds the bindings.

## Testing a model

- **Register level**, in `sim-hardware`: `WorldRig` (in the tests) steps a `DeviceHost` at 240 Hz around a `ScriptedBody`,
  a chassis that follows a script of constant-velocity moves so that the true pose is known exactly, and a test `RayView`.
  Write the register traffic as the driver does and compare with an answer worked out by hand.
- **Through the real driver**, in `acceptance-tests`: a fixture OpMode in `src/scenarios` that uses the driver as a robot
  program does, a robot built with `TestRobot.builder().chassis(...)` and `.pinpoint()`, `.colorSensor(...)`,
  `.embeddedImu(...)` or `.limelight(...)`, and a test that runs it in virtual time. Run it on every supported SDK
  (`docs/sdk-versions.md`): `gradlew :acceptance-tests:test` (11.1.0) and the same with `-PftcSdk=` 11.0.0, 11.2.0, 11.2.1 and
  12.0.0.
- **In a whole session**: `DevicesSessionTest` runs the sensors on the fixed robot in the physics world and holds the binding
  rules (`Sensors By Class` OpMode); `RobotRigTest` (`simulator`) holds that every rig has them.

## Cameras are not I2C devices

A smart camera such as the Limelight 3A is on USB and speaks HTTP, so it has its own port and registry. The model
(`dev.ftcsim.hardware.camera`, implementing `CameraModel`) answers the camera's requests with the text the camera would
send and is registered by one line in `CameraModels.standard()`; `RobotRig` plugs the one the robot has into a `CameraRack`
(the `CameraWire` the SDK adapter talks to), which names it after the first name robot code asks for. The SDK adapter parses
that text with the SDK's own classes. It reads the world through the same `DeviceContext`. See `docs/devices/limelight.md`.
