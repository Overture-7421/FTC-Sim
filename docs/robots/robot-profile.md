# Describing your robot: the robot profile

The simulator runs your robot program unchanged, and the program only knows names: `hardwareMap.get(DcMotorEx.class, "frontLeft")`.
What a name is made of, a drivetrain wheel, a flywheel, a colour sensor, a camera, is not in the code. It is in the
robot profile: one JSON file, kept outside the program, that says which hubs the robot has, what is plugged into every
port, and how the robot handles game pieces. `hornet-2026.json` is the one that ships with the simulator (see
`hornet-profile.md` for how its numbers were fitted); start from a copy of it.

Choosing one: Setup, Robot profile. The list holds the profiles that ship with the simulator; "Choose a file..." takes a
`.json` file of your own. A profile describes a robot, not a game, so the same file serves both fields (the one exception
is a camera's pipelines, which are the camera's own settings, including which game's tags it believes in).

What the profile does not do: it never decides how the robot drives or what it does with a piece. A device the code
asks for and the profile does not mention still works (it is given the next free port and the session reports it as
unbound), so a forgotten line is a warning, not a crash.

## Rules of the file

- Strict JSON, no comments. An unknown property, a missing required one, a duplicate key or a number where a word
  belongs is an error that names the place in the file (for example `Unrecognized field "wheelRadius"` with the path to
  it). A typo is never ignored, because a robot that quietly differs from its code is worse than a profile that does not
  load.
- `"schema": 1` and `"name"` are required. Everything else is optional unless it says otherwise below.
- Units: metres, kilograms, seconds, radians per second, newton-metres. Angles are in degrees where the name says so.
- The robot's own frame, for every point and direction in the file: X forward, Y left, Z up, origin at the centre of the
  chassis box (half its height plus its ground clearance above the floor when the robot stands level).
- Motor direction: a positive command is a rising encoder count. A motor that spins the other way when mounted (the left
  side of a drivetrain) is described by the wheel's `polarity`, not by anything in the code.

## What goes where

| Section | What it says |
|---|---|
| `batteryVolts` | The supply every motor is driven from; 12.6 when absent. |
| `hubs` | The REV hubs: `address` (1 to 254), `name` as the code finds it, `parent` (exactly one) and `embedded` (inside a Control Hub, address 173). The embedded Control Hub when absent. |
| `chassis` | The mecanum drivetrain body: `motor` (the drivetrain's motor from the catalogue), mass, size, wheel radius and base, ground clearance, friction numbers. All optional; every missing number comes from a generic 18 inch robot (`MecanumConfig.generic18Inch()` says what each means). A profile with wheels needs a `chassis`, and one with a `chassis` needs all four wheels driven. |
| `motors` | By the name the code uses: `hub`, `port` (0 to 3, each used once per hub) and exactly one of `wheel` (`position` FRONT_LEFT, FRONT_RIGHT, REAR_LEFT or REAR_RIGHT, and `polarity` +1 or -1; a wheel names no `motor`, it is turned by the chassis's) or `shaft` (`loadInertiaKgM2`, `frictionNmsPerRad`, and the `motor` that turns it, from the catalogue below). |
| `servos` | By name: `hub` and `port` (0 to 5). |
| `devices` | I2C devices by name: `type`, `bus` (0 to 3; bus 0 of a hub is its own IMU's) and the model's own `config`. |
| `digitals` | Digital pins by name, which the code reads as a `TouchSensor` or a `DigitalChannel`: `hub`, `channel` (0 to 7, each used once per hub) and a `source`: `constant` (`level` HIGH or LOW) or `encoder` (a limit switch on a mechanism: `motor`, `counts` and `closedWhen` ABOVE or BELOW; closed reads LOW). No `source` is a connected pin that reads HIGH. |
| `analogs` | Analog pins by name, which the code reads as an `AnalogInput`: `hub`, `channel` (0 to 3) and a `source`: `constant` (`volts`) or `encoder` (a potentiometer on a mechanism: `motor`, `voltsAtZero`, `voltsPerCount`; within 0 to 3.3 V). No `source` is a connected pin that reads 0 V. |
| `cameras` | Smart cameras by name: `type`, `mount` (position and angles in the robot frame) and the pipelines the camera was set up with. |
| `mechanisms` | What the robot does with pieces: `storages`, `intakes` and `launchers` (below). |

### Motors

The catalogue holds the goBILDA 5203 Yellow Jacket family by nominal speed (`goBILDA-5203-1620`, `-1150`, `-435`, `-312`,
`-223`, `-117`, `-84`, `-60`, `-43`, `-30`) and the bare motor (`goBILDA-bare-6000`). The 435 is the measured one; the
others are its core behind their gearbox, ideal and without losses, so their speeds are within 6 % of the nominal one.
Any other name is rejected with the list of known ones. Another motor is one entry in `MotorCatalog` (a stall torque,
a free speed, a stall current, a rotor inertia, a gear ratio and counts per revolution); REV's motors are not in it yet.

A `shaft` motor is whatever the motor turns that is not a wheel: a flywheel, an intake roller, a conveyor. Its inertia
and friction decide how long it takes to spin up and what speed it settles at for a given power, which is what your
PIDF sees. Hornet's `kF` gave its shooter friction (`hornet-profile.md`).

### Devices and cameras

Each device model reads its own `config` and rejects keys it does not know. The ones that ship:

| `type` | Model | Notes |
|---|---|---|
| `gobilda-pinpoint` | goBILDA Pinpoint odometry computer, through the real SDK driver | `xPod` and `yPod` give where each pod really is (`x`, `y`, metres), its `ticksPerMetre` and `direction`. The program's own offsets and directions are separate: a wrong one stays wrong, as on a robot. |
| `rev-color-sensor-v3` | REV Color Sensor V3 | Where it is (`x`, `y`, `z`) and which way it looks (`azimuthDegrees`, `elevationDegrees`); it reads the pieces it can see, the ones the robot carries included. |
| `octoquad-ftc-mk2` | Digital Chicken Labs OctoQuad FTC MK2, through the real SDK driver | `firmware` (`3.1.0` when absent), and per channel `channel0` to `channel7`: either `pod` (a mounted odometry pod, as the Pinpoint's) or `encoder` (the name of a motor of this robot whose shaft it counts), and `countsPerRev`. `docs/architecture/adding-a-device.md` tells how the model was added. |
| `bno055-imu` | Bosch BNO055 IMU (a hub's own, the REV 9-axis IMU, the AndyMark IMU, Adafruit's breakout), through the real SDK drivers: `IMU` and `BNO055IMU` | `address` (40 when absent, 41 for the REV 9-axis IMU), `mount.logo` and `mount.port` (UP, DOWN, FORWARD, BACKWARD, LEFT or RIGHT: the way the board's logo and its connector face on the robot; UP and FORWARD when absent) and `gyroBiasRadPerSec` (a drift of the heading, 0 when absent). A Control Hub has its IMU without a line; a line on bus 0 only says how it is mounted, and an Expansion Hub has one only when listed. What the program tells the SDK about the mounting is separate: a wrong one stays wrong, as on a robot. `docs/devices/bno055-imu.md`. |
| `limelight-3a` (cameras) | Limelight 3A | `docs/devices/limelight.md`. |

A new device model is one class and one registry line (`docs/architecture/adding-a-device.md`).

### Digital and analog pins

A limit switch, a beam break or a potentiometer is not a device model: it is a source on a pin, in `digitals` or
`analogs`, that follows the encoder of one of the profile's motors or holds a level.

```json
"digitals": { "slideLimit": { "channel": 0, "source": { "type": "encoder", "motor": "slide", "counts": 2400, "closedWhen": "ABOVE" } } },
"analogs":  { "armPot": { "channel": 0, "source": { "type": "encoder", "motor": "arm", "voltsAtZero": 0.5, "voltsPerCount": 0.0001 } } }
```

The switch closes, and its pin reads LOW as a switch to ground does, while the encoder of `slide` is above 2400 counts.
The count is the shaft's: robot code resetting its own encoder does not move the switch. A pin the profile does not
list still reads (HIGH, or 0 V) and is reported as unbound. `docs/devices/digital-and-analog.md` has the rest, with
what is not simulated.

### Pieces: storage, intake, launcher

Mechanisms are described by what their motors do, never by what the robot program is doing. Nothing here pushes on
the chassis: pieces and mechanisms follow the robot, they do not load it.

- `storages`: a `path` of points (a piece enters at the first and waits at the last), a `capacity` and the `drives`
  (rollers or belts: the `motor`, its `radiusM`, a `sign` for which way forward carries pieces, and the stretch of the
  path it `from` and `to` reaches).
- `intakes`: a box (`center`, `half`) in front of the robot. While every `when` condition holds (a motor's shaft speed
  within a range), a piece whose centre is in the box and whose diameter is between `minPieceDiameterM` and
  `maxPieceDiameterM` is taken into the `storage`. The size range is why Hornet's intake takes DECODE's 5 inch balls and
  ignores BIOBUZZ's 2.8 and 3.6 inch ones.
- `launchers`: at the end of a storage's path. When its `when` conditions hold, the piece waiting there leaves with the
  chassis' velocity plus `direction` times `efficiency` times the average rim speed of the `wheels` (`radiusM`, `sign`).
  The flywheels pay the piece's kinetic energy, so a shot slows them down. There is no spin and no air drag.

## Checking a profile

A profile is checked twice, and both say where in the file the problem is. `RobotProfileLoader` checks the structure
when the file is read: hubs, ports used once, wheels, devices and cameras (a camera model rejects the keys it does not
know), and digital and analog pins with their sources (a source names a motor of the profile). The session checks
what refers to what when it builds the mechanisms: a drive or a flywheel must name a motor of
the profile that turns a shaft, and an intake or launcher must name a storage that exists. The tests with the messages
are `RobotProfileLoaderTest` and `MechanismFactoryTest` in the `simulator` module.

Fitting a profile to a real robot is a matter of numbers, not of structure; `hornet-profile.md` shows how it was done
for Hornet: Pedro Pathing's own tuners, run on the simulated robot, measure the drivetrain, and the profile is adjusted
until they report what they report on the real one.
