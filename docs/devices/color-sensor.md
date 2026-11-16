# REV Color Sensor V3

`RevColorSensorV3Model` (sim-hardware, `dev.ftcsim.hardware.device.rev`, type id `rev-color-sensor-v3`) is the REV Color
Sensor V3, whose chip is the Broadcom APDS-9151, as the SDK's `RevColorSensorV3` talks to it, on the Control Hub's I2C bus 2.
Robot code finds it by `ColorRangeSensor`, `NormalizedColorSensor`, `ColorSensor` or `RevColorSensorV3`, under any name
(Hornet's is `hardwareMap.get(ColorRangeSensor.class, "upSensor")`); a second name for it is refused and listed among the
session's problems (`architecture/adding-a-device.md`). The robot has one, and the design fixes where it is: on the DECODE
robot, in the robot frame, 0.12 m behind the centre, 0.09 m to the left and 0.26 m up (the end of the conveyor, where a
piece waits to be shot), looking right (azimuth -90 degrees).

It measures by looking along one ray into the world (`DeviceContext.rays()`, which includes the pieces the robot carries):
proximity from how much of its infrared LED comes back, and red, green, blue and infrared light from the ambient light the
surface reflects. Proximity follows the fit REV calibrated the SDK's distance conversion with, so with the SDK's settings the
SDK reads back the true distance of a card-like surface; a darker surface reads farther (the signal scales with the
brightest channel of the colour), a very near one saturates at the SDK's 0.25 inch minimum, and nothing in range reads the
SDK's 6 inch maximum. The gain, resolution, pulse count and LED current registers scale the signal as the driver sets them,
and every measurement happens at the rate the rate registers say (100 ms with the SDK's settings). All of it is estimated,
not measured; the model's Javadoc says which numbers.

## Not modelled

Ambient light that varies (the room is evenly lit), interrupts, the sensor's own calibration, and a sensor that is not on this
bus.

## Tests

`RevColorSensorV3ModelTest` (sim-hardware) against a flat wall of known colour and distance; `DeviceModelsHostTest` through
the real SDK driver on all five SDKs; `DevicesSessionTest` reads a piece the robot carries on the fixed robot.
