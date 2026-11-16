# REV Color Sensor V3

What robot code reads from the simulated REV Color Sensor V3 (`ColorRangeSensor` and relatives), and what is estimated.

`RevColorSensorV3Model` (sim-hardware, `dev.ftcsim.hardware.device.rev`, type id `rev-color-sensor-v3`) is the sensor (chip
APDS-9151) as the SDK's `RevColorSensorV3` talks to it, on the Control Hub's I2C bus 2.

- **Binding.** By `ColorRangeSensor`, `NormalizedColorSensor`, `ColorSensor` or `RevColorSensorV3`, under any name. A second
  name is refused and listed among the session's problems (`../architecture/adding-a-device.md`).
- **Mount.** One sensor, fixed by `RobotDesign` on both robots, no team settings: 0.12 m behind the centre, 0.09 m left, 0.26 m
  up (the end of the conveyor, where a piece waits to be shot), looking right (azimuth -90 degrees). Ambient light is the default.
- **Measurement.** One ray into the world (`DeviceContext.rays()`, pieces the robot carries included), every 100 ms with the
  SDK's settings. The gain, resolution, pulse count, LED current and rate registers act as the driver sets them.
- **Proximity** follows the fit REV calibrated the SDK's distance conversion with: a card-like surface reads its true
  distance, a darker one farther, a very near one saturates at the SDK's 0.25 inch minimum, nothing in range reads its 6 inch maximum.
- **Colour** is the ambient light the surface reflects.
- **All of it is estimated, not measured**; the model's Javadoc says how.
- **Not modelled:** varying ambient light (the room is evenly lit), interrupts, the sensor's own calibration, other buses.
