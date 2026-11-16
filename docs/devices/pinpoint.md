# goBILDA Pinpoint

What the simulated goBILDA Pinpoint reports to `GoBildaPinpointDriver` and what it ignores.

`GoBildaPinpointModel` (sim-hardware, `dev.ftcsim.hardware.device.gobilda`, type id `gobilda-pinpoint`) is the Pinpoint
(3110-0002-0001) as the SDK's own `GoBildaPinpointDriver` talks to it, on the Control Hub's I2C bus 1. Pedro Pathing's
`PinpointLocalizer` runs on it unchanged. The registers follow the V3 firmware; the model's Javadoc lists them.

- **Binding.** By that driver class, under any name. A second name is refused and listed among the session's problems
  (`../architecture/adding-a-device.md`). It is fixed and takes no settings.
- **Ideal.** It reports the **true pose of the chassis**: no pods, no gyro drift, so whatever the code programs about its pods is right.
- **The frame is the code's.** Where the chassis stands when the chip becomes ready is the origin, heading zero. Writing X, Y or
  heading (`setPosition`, `setPosX`, `setPosY`, `setHeading`) moves the reported pose to that value; the chip then reports the
  true motion turned into that frame. `resetPosAndIMU` returns to the origin and calibrates; `recalibrateIMU` only calibrates.
  The heading counts on past a half turn.
- **Ignored.** Pod offsets, encoder resolution, directions and the yaw scalar are accepted and read back as written. A wrong
  pod offset does not make the pose drift here, as it would on a robot.
- **Encoder registers** count the true travel forward (X) and left (Y) at the resolution the code last wrote (until then 13.26
  ticks per mm, the swingarm pod's); directions are not applied. **Velocities** are the true ones, in the reported frame.
- **Timing (estimates).** Not ready for 100 ms after power-up, then calibrating for 250 ms, again after a reset or
  recalibration; pose and velocities are held meanwhile and the status register says so. The loop time register reads 667 us
  (1500 Hz). The chip updates once per physics step.
- **Not modelled:** pod slip or lift, a tracking point away from the chassis origin, gyro noise and drift, temperature, I2C
  errors under vibration, the firmware's gyro calibration (its time is modelled, its result is perfect).
