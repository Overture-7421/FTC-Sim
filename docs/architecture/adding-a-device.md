# Adding an ideal fixed device

The simulator runs the team's own SDK driver for a device, unchanged, against a model of the device. The driver talks I2C
to a hub's bus, the hub emulation (`sim-hardware`, `dev.ftcsim.hardware.lynx`) times and carries the transactions, and the
model answers them. The sensors of the robot are **fixed and ideal**: the robot has one of each kind, a model reports the
truth of the world with no settings a student could get wrong, and robot code finds it **by the driver class it asks
for, under any name**. So there is no per-project sensor description; adding a device is a model, a bus and a binding.

| Driver class(es) the code asks for | The sensor | Where | Page |
|---|---|---|---|
| `GoBildaPinpointDriver` | goBILDA Pinpoint (`GoBildaPinpointModel`) | Control Hub I2C bus 1 | `devices/pinpoint.md` |
| `ColorRangeSensor`, `NormalizedColorSensor`, `ColorSensor`, `RevColorSensorV3` | REV Color Sensor V3 (`RevColorSensorV3Model`) | bus 2 | `devices/color-sensor.md` |
| `IMU`, `BNO055IMU`, `LynxEmbeddedBNO055IMUNew`, `LynxEmbeddedIMU` | The Control Hub's BNO055 (`Bno055Model`) | bus 0 | `devices/bno055-imu.md` |
| `Limelight3A` | The Limelight 3A (`LimelightModel`) | USB, over HTTP | `devices/limelight.md` |

Everything else is **unsupported** (an OctoQuad, an external IMU, a webcam, a second sensor of a kind, `BHI260IMU`): the
lookup fails with the SDK's own kind of message, the device is not bound, and the session lists it among its problems
(`HardwareDirectory.unsupported`, shown by `SimulationSession.problems()`). Servos and digital and analog pins are not
sensors and are not mapped: an unmapped servo accepts commands with no effect, and an unmapped pin reads idle (a digital
pin high through the hub's pull-up, an analog pin 0 V); the name gets a free port and is reported in `SessionStatus.unmapped`.

## How a driver class is bound

`I2cDriverProvider` (`sdk-common`) decides what a request is: `kindOf(class)` maps the driver classes and interfaces to a
sensor, `create` asks `HardwareDirectory.sensor(name, kind)` where it is (one name per sensor; a second name is refused and
recorded as unsupported) and builds the SDK's own driver on the SDK's I2C transport. A request for an interface
(`ColorRangeSensor`) names no driver, so the provider says which driver implements it. `WiringTable` holds the bindings.

## Adding a model

1. **Write it** in `sim-hardware`: implement `I2cPeripheral` (or extend `RegisterFilePeripheral`) with a static factory
   `create(DeviceConfig, DeviceContext)`; their Javadoc says what a model may read and when it is called. The SDK driver is
   the specification: which registers it writes and reads, in which order and units, and what it waits for.
2. **Be ideal.** Report the truth of the world; a setting the code writes that only describes the device's mounting or tuning
   is accepted and read back but has no effect. Keep what robot code can see of the real device: its boot and calibration
   times, its register quantisation, what it holds while it calibrates. Read a design setting such as a place in the factory
   from `config` (a setting never asked for is rejected, so a typo fails loudly). Keep timers on `nowNanos` and measurements
   on the physics step: `nowNanos` can be a few milliseconds past the last step, because the hub moves the time of its I2C
   transactions on while the SDK polls.
3. **Register it**: one line in `DeviceModels.standard()` and a constant of `HardwareDirectory.Sensor` that names the sensor
   and its bus on the parent hub. `RobotRig` attaches the model with the settings `RobotDesign` gives it.
4. **Bind it by class** in `I2cDriverProvider` and `WiringTable`.

A model is only called by the scheduled side, needs no locking and must not wait for another thread ("Threads and the bus"
in the documentation of `dev.ftcsim.hardware.lynx`). A camera such as the Limelight 3A is on USB and speaks HTTP, so it is
not an I2C device: its `CameraModel` (`dev.ftcsim.hardware.camera`) answers the camera's requests with the text the camera
would send and is registered in `CameraModels.standard()` (`devices/limelight.md`).

## Testing a model

At register level in `sim-hardware`: `WorldRig` steps a `DeviceHost` around a `ScriptedBody` (a chassis that follows a script
of moves, so the true pose is known; in the test fixtures) and the test compares the register traffic with an answer worked
out by hand. Through the real driver in `acceptance-tests`: a fixture OpMode on a `TestRobot`, run on every supported SDK.
`docs/testing.md` has the layers and `docs/sdk-versions.md` the SDK releases.
