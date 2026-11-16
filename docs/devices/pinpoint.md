# goBILDA Pinpoint

`GoBildaPinpointModel` (sim-hardware, `dev.ftcsim.hardware.device.gobilda`, type id `gobilda-pinpoint`) is the goBILDA
Pinpoint Odometry Computer (3110-0002-0001) as the SDK's own `GoBildaPinpointDriver` talks to it, on the Control Hub's
I2C bus 1. Robot code finds it by that driver class under any name (`hardwareMap.get(GoBildaPinpointDriver.class, "odopods")`);
a second name for it is refused and listed among the session's problems (`architecture/adding-a-device.md`). It is fixed
and ideal and takes no settings. Pedro Pathing's `PinpointLocalizer` runs on it unchanged (offsets, pod resolution, encoder
directions, `resetPosAndIMU`, `setPosition`, then `update` and the getters every loop).

## It is ideal

The chip reports the **true pose of the chassis**; there are no pods to measure it by and no gyro to drift, so whatever the
code programs about its pods is right.

- **The frame is the code's.** Where the chassis stands when the chip becomes ready is the origin, heading zero. A write to
  the X, Y or heading register (`setPosition`, `setPosX`, `setPosY`, `setHeading`) moves the reported pose to the value
  written, and the chip goes on reporting the true motion turned into that frame. `resetPosAndIMU` puts the pose back at the
  origin and calibrates again; `recalibrateIMU` only calibrates. The heading counts on past a half turn.
- **Pod offsets, the encoder resolution, the encoder directions and the yaw scalar** are accepted, read back as written and
  ignored: a wrong pod offset does not make the pose drift here, as it would on a robot.
- **Encoder registers** count the true travel forward (X) and to the left (Y) at the resolution the code last wrote (the
  swingarm pod's 13.26 ticks per mm until then); directions are not applied. **Velocities** are the true ones, in the
  reported frame (mm/s, rad/s).
- **Timeline (estimates):** not ready for 100 ms after power-up, then calibrating for 250 ms, again after a reset or
  recalibration; the pose and velocities are held meanwhile and the status register says so. The loop time register reads
  667 us (the chip's 1500 Hz loop). The chip updates once per physics step. The registers follow the V3 firmware; the
  model's Javadoc lists them.

## Not modelled

Pod slip, a pod that lifts, a tracking point away from the chassis origin (it is the origin), gyro noise and drift,
temperature, the I2C errors a real chip gives under vibration, and the firmware's calibration of the gyro (the time is
modelled, the result is perfect).
