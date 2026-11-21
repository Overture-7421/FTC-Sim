# BNO055 IMU

What the Control Hub's simulated IMU gives robot code through `IMU` and `BNO055IMU`, and what it does not.

`Bno055Model` (sim-hardware, `dev.ftcsim.hardware.device.bosch`, type id `bno055-imu`) is a register-level model of the Bosch
BNO055 on the Control Hub's I2C bus 0. The SDK's own drivers run unchanged on it: `IMU` (`LynxEmbeddedBNO055IMUNew`) and
`BNO055IMU` (`LynxEmbeddedIMU`); there is no fake `IMU` class. The IMU code of SDK 11.0.0 to 12.0.0 is identical. The model's
Javadoc has the register and timing inventory.

## Which IMU robot code gets

`RobotRig` always attaches the Control Hub's IMU; it is the robot's only IMU. `HardwareDirectory.imu(name)` decides and
`I2cDriverProvider` (sdk-common) picks the driver, by the class the code asks for.

- `IMU.class` or `BNO055IMU.class` (or the two driver classes) bind to it under any name ("imu", "IMU", "gyro").
- **One chip, one name.** A second, different name is refused, as a configuration would refuse it, and listed among the
  session's problems. Both APIs may use the same name.
- **Every other IMU is unsupported**: an Expansion Hub's, REV 9-axis, Adafruit, AndyMark, any other driver. The lookup fails
  with a message and the name is listed among the problems (`../architecture/adding-a-device.md`).
- **The newer Control Hub's BHI260AP is not simulated**; the BNO055 stands in. Code asking for `IMU` or `BNO055IMU` works on
  both; code asking for `BHI260IMU` fails at the lookup with a message and is listed as unsupported.

## The one setting: how the hub is mounted

The robot profile's `hubLogo` and `hubUsb` (`RobotProfiles`; both robots have UP and FORWARD) say which way the hub's logo and USB ports face (UP, DOWN,
FORWARD, BACKWARD, LEFT, RIGHT, as `RevHubOrientationOnRobot` names them). They are UP and FORWARD, which the SDK assumes.
What the code tells the SDK (`IMU.Parameters(new RevHubOrientationOnRobot(...))`) is separate: the SDK applies it in software
and the chip never sees it, so **a wrong value gives wrong angles, as on a robot**. A hub lying logo up and USB forward that the
code believes has USB on the right reads the right heading and swaps pitch and roll.

## What the chip reports

All from the true chassis (`DeviceContext.body()`): orientation, angular velocity and change of velocity, sampled at the 240 Hz
physics steps. No drift, no noise.

- **Heading** is zero where the chip pointed at power-up or reset. This is the IMU mode the SDK selects (accelerometer and
  gyroscope, no magnetometer); compass and NDOF run the same, since the world has no magnetic field. Pitch and roll are
  absolute, against gravity. Positive yaw is left, pitch nose up, roll leaning right.
- **Yaw reference.** The SDK zeroes yaw when the driver is created, assuming the default mounting, so a hub mounted another way
  needs `imu.resetYaw()` after `initialize(...)`, on a robot and here.
- **Units.** Registers hold whole counts: 1/16 degree, 1/16384 of a quaternion unit, 0.01 m/s^2. Gyroscope and accelerometer
  clip at the configured range.
- **Linear acceleration** is that of the chassis origin; the chip's position on the robot is not modelled.

## Timing and estimates

| Value | Source |
|---|---|
| Reset (`SYS_TRIGGER` bit 5): 650 ms of silence (registers read 0xFF), then defaults | Datasheet power-on time (typical); the SDK waits 400 ms and polls |
| Outputs read zero for 50 ms after entering an operating mode | The SDK's own comment |
| Outputs refresh at 100 Hz from the latest physics step (8.3 to 12.5 ms) | Datasheet fusion output rate |
| Calibration: gyroscope 3 after 3 s in a mode, accelerometer after 6 s, magnetometer never, system the lowest used | **Estimate** |
| Temperature 30 degrees C constant | **Estimate** |
| Revision registers 0xA0, 0xFB, 0x32, 0x0F; software 0x0311, bootloader 0x15 | Datasheet; the unique id is made up |

The chip answers from the first instant, in CONFIG mode. The SDK drivers' waits run on simulated time (a legacy `initialize`
takes about 0.85 s of it, never real time). `resetYaw` polls inside a synchronized method where physics cannot step, so an
output overdue by a whole period is latched from the latest sample (the model's Javadoc says why).

## Not modelled

The magnetometer (reads zero) and a magnetic heading; interrupts, self-test commands, power modes, clock selection (registers
hold what was written); the calibration algorithm and stored profiles (offsets are stored and read back, and change nothing);
noise, temperature drift, vibration, a chip away from the chassis origin; the BHI260AP; an Expansion Hub's IMU; external IMUs;
the Servo Hub (it has no IMU).
