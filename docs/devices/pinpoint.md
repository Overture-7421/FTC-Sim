# goBILDA Pinpoint

`GoBildaPinpointModel` (sim-hardware, `dev.ftcsim.hardware.device.gobilda`, type id `gobilda-pinpoint`) is the goBILDA
Pinpoint Odometry Computer (3110-0002-0001) as the SDK's own `GoBildaPinpointDriver` talks to it, on the Control Hub's
I2C bus 1. Robot code finds it by that driver class under any name (`hardwareMap.get(GoBildaPinpointDriver.class, "odopods")`);
a second name for it is refused and listed among the session's problems (`architecture/adding-a-device.md`). Pedro Pathing's
`PinpointLocalizer` runs on it unchanged (Hornet's does: offsets, the 4-bar pod's resolution, both encoder directions
reversed, `resetPosAndIMU`, `setPosition`, then `update` and the pose and velocity getters every loop).

## It is ideal

The chip reports the **true pose of the chassis**. There are no odometry pods to measure it by and no gyro to drift, so the
pose is not estimated and whatever the code programs about its pods is right:

- **What is the code's is the frame** the pose is reported in. Where the chassis stands when the chip becomes ready is the
  origin, heading zero. A write to the X, Y or heading position register (`setPosition`, `setPosX`, `setPosY`,
  `setHeading`) moves the reported pose to the value written, from the true pose of that moment, and the chip goes on
  reporting the chassis's true motion turned into that frame. The reset control bit (`resetPosAndIMU`) puts the pose back
  at the origin and the chip calibrates again; the recalibrate bit (`recalibrateIMU`) only calibrates.
- **Pod offsets, the encoder resolution, the encoder directions and the yaw scalar are accepted and ignored**, and read
  back as written. A pod offset the code got wrong does not make the pose drift here, as it would on a robot
  (`DeviceModelsHostTest`, `GoBildaPinpointModelTest`).
- **The encoder registers** count the true travel forward (X) and to the left (Y), in the chip's resolution as the code
  last wrote it (the swingarm pod's 13.26 ticks per mm until then); the directions are not applied.
- **Velocities** are the true ones, in the reported frame (mm/s, rad/s). The heading counts on past a half turn.
- **No pods, no dead reckoning, no gyro bias**, and no setting of any kind: the model refuses any it is given.

## Timeline (estimates)

After power-up the chip is not ready for 100 ms and then calibrates for 250 ms, as it does again after a reset or a
recalibration; while it is not ready or calibrating the pose and velocities are held and the status register says so
(`NOT_READY`, `CALIBRATING`, `READY`). The loop time register reads 667 us (the chip's 1500 Hz loop). The chip updates once per
physics step. Registers follow the V3 firmware: identity 2, version 3, bulk read (default scope and `SET_BULK_READ`),
quaternion, pitch and roll, each single-register read with the CRC-8 the driver checks.

## Not modelled

Real odometry: pod slip, a pod that lifts, a tracking point away from the chassis origin (the tracking point is the origin),
gyro noise and drift, temperature, the I2C bus errors a real chip gives under vibration, and the firmware's own calibration of
the gyro (the time is modelled, the result is perfect).

## Tests

`GoBildaPinpointModelTest` (sim-hardware) reads and writes the registers as the driver does against a scripted chassis.
`DeviceModelsHostTest` runs Pedro's sequence through the real SDK driver (all five SDKs), and `DevicesSessionTest` reads the
pose of the whole robot in the physics world against the chassis's own.
