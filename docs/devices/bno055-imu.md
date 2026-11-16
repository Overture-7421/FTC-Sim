# BNO055 IMU

The Bosch BNO055 is the IMU inside the REV Control Hub (the older one, see "The newer Control Hub" below). `Bno055Model`
(sim-hardware, `dev.ftcsim.hardware.device.bosch`, type id `bno055-imu`) is a register-level model of the chip on the
Control Hub's I2C bus 0, and the SDK's own drivers run unchanged against it, as they do against a real chip: `IMU`
(new API, `LynxEmbeddedBNO055IMUNew`) and `BNO055IMU` (legacy API, `LynxEmbeddedIMU`). There is no fake `IMU` class
anywhere. The IMU sources of SDK 11.0.0, 11.1.0, 11.2.0, 11.2.1 and 12.0.0 are identical, and the tests pass on all five.

## Which IMU robot code gets

The Control Hub's IMU is the only IMU the simulated robot has, and the rig attaches it always. It is found by the driver
class the code asks for, under any name:

1. `hardwareMap.get(IMU.class, name)` and `hardwareMap.get(BNO055IMU.class, name)` (and `LynxEmbeddedBNO055IMUNew` or
   `LynxEmbeddedIMU` by class) bind to it under any name: "imu", "IMU", "gyro". No name is special.
2. **One chip, one name.** A second, different name is refused, as a configuration would refuse it: `Unable to find a
   hardware device with name "gyro" and type IMU: ... "imu" already is it`; the session lists it among its problems. The
   same name may be asked for by both APIs, as a configuration with both drivers allows.
3. **Every other IMU is unsupported**: an Expansion Hub's, an external REV 9-axis, Adafruit or AndyMark IMU, any other
   driver. The lookup fails with a message and the session lists the name among its problems (`architecture/adding-a-device.md`).

The wiring decision is `HardwareDirectory.imu(name)` (sim-contracts); the driver choice is `I2cDriverProvider`
(sdk-common); the chip is attached by `RobotRig`.

## The one setting: how the hub is mounted

The chip is ideal, with no settings of its own except how the Control Hub sits on the robot: `RobotMapping.hubLogo` and
`hubUsb` (the Robot page's two dropdowns), the directions the hub's logo and its USB ports face, as the SDK's
`RevHubOrientationOnRobot` names them (UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT, at right angles). They default to UP and
FORWARD, which is what the SDK assumes. What the robot code tells the SDK (`IMU.Parameters(new RevHubOrientationOnRobot(...))`)
is separate: the SDK applies the code's orientation in software, so the chip never sees it, and a wrong value gives wrong
angles, as on a robot. A hub that really lies logo up and USB forward, which code believes has its USB ports on the right,
reads the right heading and swaps pitch and roll.

## The newer Control Hub

Newer Control Hubs have a BHI260AP IMU, which has a different driver (`BHI260IMU`) and a firmware upload. It is not
simulated: the simulated Control Hub is the older one. Code that asks for the `IMU` interface or `BNO055IMU` cannot tell
and works on both. Code that asks for `BHI260IMU` by class fails at the lookup with a message that says so and what to
ask for instead, and the session lists it as unsupported.

## What the chip reports

Everything comes from the true chassis (`DeviceContext.body()`): its orientation, angular velocity and the change of its
velocity, sampled at the physics steps. Nothing the robot code tells the SDK reaches the chip.

- **Heading** is zero where the chip pointed when it last powered up or reset (for a chip that lies flat, where the
  chassis faced), as in the IMU mode (accelerometer and gyroscope, no magnetometer) that the SDK drivers select. The
  compass and NDOF modes run as the IMU mode does, since the world has no magnetic field. Pitch and roll are absolute,
  against gravity.
- **Yaw reference.** The SDK driver zeroes the yaw when it is created, assuming the default mounting, before the code can
  say otherwise; a hub mounted another way needs `imu.resetYaw()` after `initialize(...)`, on a robot and here. The
  SDK's `IMU` documents positive yaw to the left, pitch positive nose up, roll positive leaning right.
- **Registers.** Chip identity, system status and error, the unit selection and the sensor ranges (CONFIG mode only),
  accelerometer, gyroscope, Euler angles, quaternion, linear acceleration, gravity and temperature in the units the
  driver selected, calibration status, the axis remap, the calibration offsets and the second register page. Gyroscope
  and accelerometer clip at the range configured. Quantities are whole register counts: 1/16 degree, 1/16384 of a
  quaternion unit, 0.01 m/s^2.
- **No imperfection.** There is no drift and no noise.

## Timing and numbers that are estimates

| Value | Source |
|---|---|
| Chip powered long before the program: it answers from the first instant, in CONFIG mode | A robot's chips are powered before its program runs |
| Reset (`SYS_TRIGGER` bit 5): 650 ms of silence (every register reads 0xFF), then defaults | Datasheet power-on time (typical); the SDK driver waits 400 ms and polls |
| After entering an operating mode the outputs read zero for 50 ms | The SDK's own comment ("about 50 ms before it starts returning valid data") |
| Outputs refresh at 100 Hz from the latest physics step (an interval of 8.3 to 12.5 ms) | Datasheet fusion output rate; steps are 240 Hz |
| Calibration: gyroscope 3 after 3 s in a mode, accelerometer after 6 s, magnetometer never, system the lowest used | **Estimates** |
| Temperature 30 degrees C constant (Fahrenheit: 2 degrees per count) | **Estimate** |
| Chip revision registers 0xA0, 0xFB, 0x32, 0x0F; software revision 0x0311, bootloader 0x15; unique id fixed and not a real chip's | Datasheet values; the id is made up |
| Linear acceleration is the acceleration of the chassis origin; the chip's position on the robot is not modelled | A modelling choice |

The SDK drivers' own waits run on simulated time, so a legacy `initialize` takes about 0.85 s of simulated time and
never busy-waits in real time. The SDK's `resetYaw` polls the chip inside a synchronized method for up to half a
second, where the physics world cannot step; a chip whose output is overdue by a whole period therefore latches it from
the latest sample when it is read, which is what lets that poll find data (the model's Javadoc says why).

## Not modelled

The magnetometer (reads zero) and a magnetic heading; the interrupts, the self-test commands, the power modes and the
clock selection (their registers hold what was written); the chip's own calibration algorithm and its stored profiles
(calibration offsets are stored and read back, and change nothing); noise, temperature drift, vibration, the lever arm
of a chip away from the chassis origin; the BHI260AP; an Expansion Hub's IMU; external IMUs; the Servo Hub (it has no IMU).

## Tests

`Bno055ModelTest` (sim-hardware) reads and writes registers as the drivers do and compares with answers worked out by
hand. `ImuHostTest` runs the real drivers on a scripted chassis: a turn, the 24 mountings of a hub against the SDK's own
rotations, wrong mountings, the legacy API, the names, the BHI260 refusal and the slicing of simulated time.
`ImuSessionTest` runs them on the fixed robot mapped as Hornet in the physics world: the wheels turn the chassis, a ramp
tips it, a hub on its edge reads right, and a second name is a problem of the session. `RobotRigTest` (simulator) checks
that every rig has the chip on the Control Hub's bus 0.
