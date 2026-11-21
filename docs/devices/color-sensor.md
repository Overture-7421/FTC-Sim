# REV Color Sensor V3

What robot code reads from the simulated REV Color Sensor V3 (`ColorRangeSensor` and relatives), and what is estimated.

`RevColorSensorV3Model` (sim-hardware, `dev.ftcsim.hardware.device.rev`, type id `rev-color-sensor-v3`) is the sensor (chip
APDS-9151) as the SDK's `RevColorSensorV3` talks to it, on the I2C bus of its hub the robot declares (the first sensor of a robot is on the Control Hub's bus 2, the second on the Expansion Hub's bus 0).

- **Binding.** By `ColorRangeSensor`, `NormalizedColorSensor`, `ColorSensor` or `RevColorSensorV3`, **by the name the robot declares for it**: the lookup
  of a name the robot does not declare fails with a message listing the declared names and is recorded among the session's problems (`../architecture/adding-a-device.md`). The same name finds the same sensor again, and a robot may declare several, each on its own hub and bus.
- **Mount.** Each built-in robot declares its sensors in `RobotProfiles` (nothing is set by a team): Hornet one, `upSensor`, on the Control Hub's bus 2, 0.12 m behind the centre, 0.09 m left, 0.26 m
  up (the end of the conveyor, where a piece waits to be shot), looking right (azimuth -90 degrees); BIOBUZZ two, both on its left wall (y = +0.065 m) looking right: `upSensor` (Control Hub bus 2) at (0.089, 0.065, 0.060), level with the ball waiting at the stopper, so it reads a ball only when one has reached the stopper, and `intakeSensor` (Expansion Hub bus 0) at (0.187, 0.065, -0.112), level with the fourth ball of the queue, so it reads only when the robot is full ([fixed-robots](../robots/fixed-robots.md)). The Control Hub's four I2C buses are taken (IMU 0, Pinpoint 1, colour 2, OctoQuad 3), hence the Expansion Hub for the second. Ambient light is the default. **The mounts are estimates**: the CAD has no sensors; replace them with measured ones in `RobotProfiles`.
- **Checked on BIOBUZZ** (`BiobuzzColourSensorsSessionTest`, the stub program reads both with `getDistance(CM)` and shows `Sensors : up=.. intake=..`): with the four preloads queued against the stopper both read a ball (`upSensor` 2.6 cm, `intakeSensor` under 5 cm); after all four are shot both read the 6 inch maximum (15.2 cm); the ball that stays near the intake when the stopper lets the other three go is seen only by `intakeSensor`, and once the intake's staging run has carried it to the stopper only by `upSensor`; a lookup under another name is refused with the declared names. Moving a mount by 11 cm makes the test fail. Three balls queued cannot be arranged by shooting (the open stopper lets three go at once, and the reserve of the field is empty), so that case is not pinned.
- **Measurement.** One ray into the world (`DeviceContext.rays()`, pieces the robot carries included), every 100 ms with the
  SDK's settings. The gain, resolution, pulse count, LED current and rate registers act as the driver sets them.
- **Proximity** follows the fit REV calibrated the SDK's distance conversion with: a card-like surface reads its true
  distance, a darker one farther, a very near one saturates at the SDK's 0.25 inch minimum, nothing in range reads its 6 inch maximum.
- **Colour** is the ambient light the surface reflects.
- **All of it is estimated, not measured**; the model's Javadoc says how.
- **Not modelled:** varying ambient light (the room is evenly lit), interrupts, the sensor's own calibration, other buses.
