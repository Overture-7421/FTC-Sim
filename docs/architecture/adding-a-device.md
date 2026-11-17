# Adding an ideal fixed device

How a sensor model is bound to the team's own SDK driver, and how to add one; read it before you add a device.

The simulator runs the team's SDK driver unchanged against a model of the device. The driver talks I2C to a hub's bus, the hub emulation (`sim-hardware`, `dev.ftcsim.hardware.lynx`) times and carries the transactions, and the model answers. Sensors are **fixed and ideal**: the robot has one of each kind, a model reports the truth of the world with no settings a student could get wrong, and robot code finds it **by the driver class it asks for, under any name**. So there is no per-project sensor description: adding a device is a model, a bus and a binding.

| Driver class(es) the code asks for | Sensor (model) | Where | Page |
|---|---|---|---|
| `GoBildaPinpointDriver` | goBILDA Pinpoint (`GoBildaPinpointModel`) | Control Hub I2C bus 1 | [pinpoint](../devices/pinpoint.md) |
| `ColorRangeSensor`, `NormalizedColorSensor`, `ColorSensor`, `RevColorSensorV3` | REV Color Sensor V3 (`RevColorSensorV3Model`) | bus 2 | [color sensor](../devices/color-sensor.md) |
| `IMU`, `BNO055IMU`, `LynxEmbeddedBNO055IMUNew`, `LynxEmbeddedIMU` | Control Hub's BNO055 (`Bno055Model`) | bus 0 | [BNO055](../devices/bno055-imu.md) |
| `Limelight3A` | Limelight 3A (`LimelightModel`) | USB, over HTTP | [Limelight](../devices/limelight.md) |
| `OctoQuad` (driver `OctoQuadImpl`) | Digital Chicken Labs OctoQuad (`OctoQuadModel`), only on a robot whose design puts encoders on it | bus 3 | [OctoQuad](../devices/octoquad.md) |

**Everything else is unsupported** (an external IMU, a webcam, a second sensor of a kind, `BHI260IMU`, an OctoQuad on a robot that has none): the lookup fails with the SDK's own kind of message, the device is not bound, and the session lists it among its problems (`HardwareDirectory.unsupported`, shown by `SimulationSession.problems()`). A sensor a robot may lack (the OctoQuad) is declared by the design (`WiringTable.Builder.sensor`, asked with `HardwareDirectory.hasSensor`).

Digital and analog pins are not sensors and are not mapped. A servo is mapped only when the design wires it (the BIOBUZZ turret's continuous-rotation servo, [fixed-robots](../robots/fixed-robots.md)); any other servo accepts commands with no effect. An unmapped pin reads idle (digital high through the hub's pull-up, analog 0 V). The name gets a free port and is listed in `SessionStatus.unmapped`.

## How a driver class is bound

`I2cDriverProvider` (`sdk-common`) decides what a request is; `WiringTable` holds the bindings.
- `kindOf(class)` maps driver classes and interfaces to a sensor. A request for an interface (`ColorRangeSensor`) names no driver, so the provider says which driver implements it.
- `create` asks `HardwareDirectory.sensor(name, kind)` where it is (one name per sensor; a second name is refused and recorded as unsupported) and builds the SDK's own driver on the SDK's I2C transport.

## Adding a model

1. **Write it** in `sim-hardware`: implement `I2cPeripheral` (or extend `RegisterFilePeripheral`) with a static factory `create(DeviceConfig, DeviceContext)`; their Javadoc says what a model may read and when it is called. The SDK driver is the specification: which registers it writes and reads, in which order and units, and what it waits for.
2. **Be ideal.** Report the truth of the world.
   - A setting the code writes that only describes mounting or tuning is accepted and read back, with no effect.
   - Keep what robot code can see of the real device: boot and calibration times, register quantisation, what it holds while calibrating.
   - Read a design setting (such as a place in the factory) from `config`; a setting never asked for is rejected, so a typo fails loudly.
   - Keep timers on `nowNanos` and measurements on the physics step. `nowNanos` can be a few milliseconds past the last step, because the hub moves its I2C transactions' time on while the SDK polls.
3. **Register it:** one line in `DeviceModels.standard()` and a constant of `HardwareDirectory.Sensor` naming the sensor and its bus on the parent hub. `RobotRig` attaches the model with the settings `RobotDesign` gives it.
4. **Bind it by class** in `I2cDriverProvider` and `WiringTable`.

A model is called only by the scheduled side, needs no locking and must not wait for another thread ("Threads and the bus" in the documentation of `dev.ftcsim.hardware.lynx`).

A camera such as the Limelight 3A is on USB and speaks HTTP, so it is not an I2C device: its `CameraModel` (`dev.ftcsim.hardware.camera`) answers the camera's requests with the text the camera would send and is registered in `CameraModels.standard()` ([Limelight](../devices/limelight.md)).

## Testing a model

- **Register level**, in `sim-hardware`: `WorldRig` steps a `DeviceHost` around a `ScriptedBody` (a chassis that follows a script of moves, so the true pose is known; in the test fixtures), and the test compares the register traffic with an answer worked out by hand.
- **Through the real driver**, in `acceptance-tests`: a fixture OpMode on a `TestRobot`, run on every supported SDK.

Layers: [../testing.md](../testing.md); SDK releases: [../sdk-versions.md](../sdk-versions.md).
