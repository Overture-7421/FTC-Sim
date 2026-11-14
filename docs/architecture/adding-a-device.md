# Adding a device

The simulator runs the team's own SDK driver for a device, unchanged, against a model of the device. The driver
talks I2C to a hub's bus, the hub emulation (`sim-hardware`, `dev.ftcsim.hardware.lynx`) times and carries the
transactions, and the model answers them. Nothing else knows what devices exist: not the chassis, the field, the UI
or the SDK adapter. Adding a device is three steps, shown here with the OctoQuad FTC Edition MK2
(`dev.ftcsim.hardware.device.digitalchickenlabs.OctoQuadModel`).

## 1. Write the model

A model is a class in `sim-hardware` that implements `I2cPeripheral` (or extends `RegisterFilePeripheral` for the
common pointer-and-auto-increment register map) and has a static factory
`create(DeviceConfig config, DeviceContext context)`.

- **The SDK driver is the specification.** Read it for exactly which registers it writes and reads, in which
  order, in which units, and what it waits for. The OctoQuad driver issues a command and its operands in one
  write and reads results back from the command data registers; the model does that in `afterWrite`, and
  refreshes the registers that change with time in `beforeRead`, so a multi-byte read is one coherent snapshot.
- **Settings** come from `config`: `config.number("channel0.pod.x")`, `config.text("firmware", "3.1.0")`. Every
  getter names the device and the setting when it fails, and a setting the factory never asked for is rejected, so a
  typo in a robot profile fails loudly. Read all the settings in the factory, not later.
- **The world** is only what `DeviceContext` offers: `body()` is where the chassis really is (`BodyPose`),
  `rays()` is what a ray meets first (`SurfaceHit`), `nowNanos()` is the clock, and `encoder(name)` is the encoder
  of a mechanism the profile names. A model reads the world when it needs it, and the time of a bus access is the
  `nowNanos` it is given. The SDK polls inside a lock, where the world cannot advance, so while it polls, the hub
  moves the time of its I2C transactions on by the time each poll takes; `nowNanos` can then be a few milliseconds
  later than the last physics step, and what a model reads from the world is as of that step. Keep timers on
  `nowNanos` and measurements on the step. Work that must happen once per physics step is a
  `context.onStep(listener)` registered in the factory; the host calls it in registration order after every 240 Hz
  step.
- **Threads.** A model is only called by the scheduled side: the I2C engine reaches a peripheral when the scheduled
  side lets simulated time pass for it, never from a thread the simulation does not schedule, which can only start and
  collect transactions (and the bus counts that as a change that makes the run unreproducible). So a model needs no
  locking of its own and must not wait for another thread. See "Threads and the bus" in the documentation of
  `dev.ftcsim.hardware.lynx`.
- **Share, do not copy.** The two-wheel dead reckoning of the OctoQuad's localizer is `DeadReckoner`, the gyro is
  `GyroHeading`, and the tracking wheels are `OdometryPod`s built from a `PodMount`; the goBILDA Pinpoint uses the
  same classes. A pod is a `EncoderSource` that turns the true pose of the chassis into encoder counts, including
  the sideways swing of a mount that is away from the robot's centre when the robot turns, so a configuration that
  does not match the mounting shows up as drift, as it does on a robot.
- **Be the device, with its limits.** The model returns what the real chip returns for the same inputs: counts are
  whole numbers, a 16-bit register saturates, a setting that takes effect only at the next reset takes effect only
  then. Estimates (timings, noise, calibration constants) are documented in the model's Javadoc as estimates.

## 2. Register it

One line in `DeviceModels.standard()`, which is the only place that lists the devices:

```java
.register("octoquad-ftc-mk2", OctoQuadModel::create)
```

The string is the type id that robot profiles use.

## 3. Use it

A robot profile places the device on a bus of a hub under the name robot code uses, with its type id and settings.
The OctoQuad of a robot with two tracking wheels and a flywheel encoder:

```json
{
  "name": "octoquad", "type": "octoquad-ftc-mk2", "hub": "Control Hub", "bus": 2,
  "settings": {
    "channel0.pod.x": 0.02,   "channel0.pod.y": -0.1135, "channel0.pod.ticksPerMetre": 19894.37,
    "channel1.pod.x": -0.004, "channel1.pod.y": 0.03, "channel1.pod.axisDegrees": 90, "channel1.pod.ticksPerMetre": 19894.37,
    "channel2.encoder": "flywheel"
  }
}
```

The robot program is untouched: `hardwareMap.get(OctoQuad.class, "octoquad")`, `setAllLocalizerParameters(...)`,
`readLocalizerDataAndAllEncoderData(...)`. If robot code asks for the device by an interface rather than by the
driver class (`OctoQuad`, `ColorRangeSensor`), the SDK adapter needs to know which driver implements it, which is
one entry in `DEFAULT_DRIVERS` of `I2cDriverProvider`; asking by the driver class itself needs nothing.

## The models that exist

Each is one class in `dev.ftcsim.hardware.device`, with its notes in `docs/robots/robot-profile.md`:

- `gobilda-pinpoint`: goBILDA Pinpoint odometry computer (`GoBildaPinpointModel`).
- `rev-color-sensor-v3`: REV Color Sensor V3 (`RevColorSensorV3Model`).
- `octoquad-ftc-mk2`: Digital Chicken Labs OctoQuad (`OctoQuadModel`).
- `bno055-imu`: Bosch BNO055 IMU, the Control Hub's own and the external ones (`bosch.Bno055Model`,
  `docs/devices/bno055-imu.md`). It is the one device a robot has without listing it, and the one that wires itself by
  the interface robot code asks for rather than by a name, so it also touched `HardwareDirectory`, `WiringTable`,
  `RobotRig` and `I2cDriverProvider`.

## Testing a model

- **Register level**, in `sim-hardware`: `WorldRig` (in the tests) steps a `DeviceHost` at 240 Hz around a
  `ScriptedBody`, a chassis that follows a script of constant-velocity moves so that the true pose is known
  exactly, and a test `RayView`. Write the register traffic as the driver does and compare with an answer worked
  out by hand: a straight line, a spin in place, an arc; the right configuration and a wrong one.
- **Through the real driver**, in `acceptance-tests`: a fixture OpMode in `src/scenarios` that uses the driver as a
  robot program does, a robot built with `TestRobot.builder().chassis(...).device(...)`, and a test that runs it in
  virtual time, once and with other slicings of simulated time, which must not change a reading. Run it on every
  supported SDK (`docs/sdk-versions.md`): `gradlew :acceptance-tests:test` (11.1.0) and the same with `-PftcSdk=` 11.0.0,
  11.2.0, 11.2.1 and 12.0.0.

## Cameras are not I2C devices

A smart camera such as the Limelight 3A is on USB and speaks HTTP, so it has its own port and registry. The model
(`dev.ftcsim.hardware.camera`, implementing `CameraModel`) answers the camera's requests with the text the camera would
send, is registered by one line in `CameraModels.standard()`, and is listed in a profile's `cameras` section by the name
robot code uses; the SDK adapter parses that text with the SDK's own classes. It reads the world through the same
`DeviceContext`. See `docs/devices/limelight.md`.

## Switches and potentiometers are not devices

A limit switch, a beam break or a potentiometer sits on a digital or analog pin of a hub, which the SDK reads through
its own `DigitalChannel`, `TouchSensor` and `AnalogInput`, so there is nothing to model. A robot profile's `digitals`
and `analogs` sections connect a source to a pin (`dev.ftcsim.hardware.pin.PinSources`: a level, or a switch or
potentiometer that follows the encoder of one of the profile's motors). See `docs/devices/digital-and-analog.md`.
