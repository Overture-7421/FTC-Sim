# BNO055 IMU

The Bosch BNO055 is the IMU inside the REV Control Hub (the older one, see "The newer Control Hub" below). `Bno055Model`
(sim-hardware, `dev.ftcsim.hardware.device.bosch`, type id `bno055-imu`) is a register-level model of the chip on the
Control Hub's I2C bus 0, and the SDK's own drivers run unchanged against it: `IMU` (new API, `LynxEmbeddedBNO055IMUNew`) and
`BNO055IMU` (legacy API, `LynxEmbeddedIMU`). There is no fake `IMU` class anywhere. The IMU sources of SDK 11.0.0 to 12.0.0
are identical. The model's Javadoc holds the register and timing inventory.

## Which IMU robot code gets

The Control Hub's IMU is the only IMU the simulated robot has, always attached by `RobotRig`. It is found by the driver
class the code asks for (`HardwareDirectory.imu(name)` decides, `I2cDriverProvider` in sdk-common picks the driver):

1. `hardwareMap.get(IMU.class, name)` and `hardwareMap.get(BNO055IMU.class, name)` (or the two driver classes) bind to it
   under any name ("imu", "IMU", "gyro").
2. **One chip, one name.** A second, different name is refused as a configuration would refuse it (`Unable to find a
   hardware device with name "gyro" and type IMU: ... "imu" already is it`) and listed among the session's problems. The
   same name may be asked for by both APIs.
3. **Every other IMU is unsupported**: an Expansion Hub's, an external REV 9-axis, Adafruit or AndyMark IMU, any other
   driver. The lookup fails with a message and the name is listed among the problems (`architecture/adding-a-device.md`).

## The one setting: how the hub is mounted

The chip is ideal and has no settings except how the Control Hub sits on the robot: `RobotMapping.hubLogo` and `hubUsb`
(the Robot page's two dropdowns), the directions the hub's logo and USB ports face, as the SDK's `RevHubOrientationOnRobot`
names them (UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT, at right angles). They default to UP and FORWARD, which is what the SDK
assumes. What the code tells the SDK (`IMU.Parameters(new RevHubOrientationOnRobot(...))`) is separate: the SDK applies it in
software and the chip never sees it, so a wrong value gives wrong angles, as on a robot. A hub that lies logo up and USB
forward, which the code believes has its USB ports on the right, reads the right heading and swaps pitch and roll.

## The newer Control Hub

Newer Control Hubs have a BHI260AP IMU, which has a different driver (`BHI260IMU`) and a firmware upload. It is not
simulated: a BNO055 model stands in for it. Code that asks for the `IMU` interface or `BNO055IMU` cannot tell and works on
both. Code that asks for `BHI260IMU` by class fails at the lookup with a message that says so and what to ask for instead,
and the session lists it as unsupported.

## What the chip reports

Everything comes from the true chassis (`DeviceContext.body()`): orientation, angular velocity and the change of its
velocity, sampled at the physics steps. There is no drift and no noise.

- **Heading** is zero where the chip pointed when it last powered up or reset (for a chip that lies flat, where the chassis
  faced), as in the IMU mode (accelerometer and gyroscope, no magnetometer) the SDK drivers select; the compass and NDOF
  modes run the same, since the world has no magnetic field. Pitch and roll are absolute, against gravity. The SDK's `IMU`
  documents positive yaw to the left, pitch positive nose up, roll positive leaning right.
- **Yaw reference.** The SDK driver zeroes the yaw when it is created, assuming the default mounting, so a hub mounted
  another way needs `imu.resetYaw()` after `initialize(...)`, on a robot and here.
- **Units.** Registers hold whole counts in the units the driver selected: 1/16 degree, 1/16384 of a quaternion unit,
  0.01 m/s^2. Gyroscope and accelerometer clip at the range configured.
- **Linear acceleration** is that of the chassis origin; the chip's position on the robot is not modelled.

## Numbers and estimates

| Value | Source |
|---|---|
| Reset (`SYS_TRIGGER` bit 5): 650 ms of silence (every register reads 0xFF), then defaults | Datasheet power-on time (typical); the SDK driver waits 400 ms and polls |
| After entering an operating mode the outputs read zero for 50 ms | The SDK's own comment |
| Outputs refresh at 100 Hz from the latest physics step (8.3 to 12.5 ms; steps are 240 Hz) | Datasheet fusion output rate |
| Calibration: gyroscope 3 after 3 s in a mode, accelerometer after 6 s, magnetometer never, system the lowest used | **Estimates** |
| Temperature 30 degrees C constant | **Estimate** |
| Chip revision registers 0xA0, 0xFB, 0x32, 0x0F; software revision 0x0311, bootloader 0x15 | Datasheet values; the unique id is made up |

The chip is powered long before the program, so it answers from the first instant, in CONFIG mode. The SDK drivers' waits run
on simulated time (a legacy `initialize` takes about 0.85 s of it and never busy-waits in real time). `resetYaw` polls the
chip inside a synchronized method where the physics world cannot step, so an output overdue by a whole period is latched from
the latest sample when it is read (the model's Javadoc says why).

## Not modelled

The magnetometer (reads zero) and a magnetic heading; interrupts, self-test commands, power modes and clock selection
(their registers hold what was written); the chip's calibration algorithm and stored profiles (the offsets are stored and
read back and change nothing); noise, temperature drift, vibration, the lever arm of a chip away from the chassis origin; the
BHI260AP; an Expansion Hub's IMU; external IMUs; the Servo Hub (it has no IMU).
