# The built-in robots

This page says which robots FTC-Sim simulates: Hornet's and BIOBUZZ's, both built into the simulator. A team's project is run on the robot of the game it plays; nothing about the robot is read from the team's project.

**The robots are fixed, and sensors are fixed and ideal.** The simulator is the team's own, so its two robots are data in code: `dev.ftcsim.simulator.robot.RobotProfiles` holds each as a `RobotMapping` (which motor name of the team's code is which part, the motor model, ratio and mirroring, the body numbers, the hub mounting, the Limelight mount, the servos and the colour sensors), and `RobotDesign` turns it into the `RobotSpec` the simulator builds from (the numbers of the robot the simulator was fitted to: [hornet-fit.md](hornet-fit.md)). Nothing is edited in a window and no file is read from a project: the session request names a field, and the worker builds the robot of that field with `RobotDesign.forField(field).spec()` ([link](../architecture/link.md)). To refit a robot, edit `RobotProfiles` ([development](../development.md)); `RobotProfilesTest` pins every number of both.

| Field | Robot |
|---|---|
| `biobuzz` | BIOBUZZ's profile (`RobotProfiles.biobuzz()`). |
| `decode` and any other field, the bare floor too | Hornet's profile (`RobotProfiles.hornet()`). |

A name the team's code asks the hardware map for that the profile lacks (a second flywheel it calls something else, another servo) is not an error: it gets a free port, appears in `SessionStatus.unmapped`, and the problems list says the robot has no part by that name (below).

## The designs

| Game | Robot |
|---|---|
| DECODE | Four mecanum wheels, an intake roller, a conveyor, a shooter of two flywheels. No turret. |
| BIOBUZZ | The same robot plus a **turret** that turns the launcher (and the Limelight) about the vertical axis, driven by a continuous-rotation servo, with two absolute encoders on an OctoQuad, a hood, a stopper and two colibri arms. |

**A part exists only when the profile gives it a motor.** Each part has its own rotor load and friction (`RobotDesign`); `RobotDesignTest` spells out every constant of Hornet's robot, so changing a fitted number fails a test.

| Part | Design |
|---|---|
| Chassis | Generic 18 inch mecanum (0.36 m wheelbase and track) with the fitted losses; mass, wheel diameter, drive motor and its ratio come from the profile. Without all four wheels there is no drivetrain. |
| Hubs | Control Hub (address 173, embedded) and Expansion Hub (address 2). |
| Storage | One path (DECODE's: three points, three pieces; BIOBUZZ's: seven, through the indexer of the CAD, [biobuzz-cad](biobuzz-cad.md), four pieces). A session or world reset starts it full: DECODE two purple and a green artifact, BIOBUZZ four POLLEN, all its preloads (`FieldRun.robotPreloads`). |
| Intake, conveyor | The intake takes in the game's pieces (DECODE 0.10 to 0.15 m across, BIOBUZZ 0.06 to 0.085 m) in a zone in front while its roller turns in at 10 rad/s or more. The roller (radius 0.025 m) drives the first 0.20 m of the path; the conveyor (radius 0.02 m) drives from 0.15 m on. |
| Shooter | Launcher 55 degrees up and forward (DECODE; BIOBUZZ's 45 degrees plus the hood, from where the hood's end lets the ball go off the flywheels' rim, which swings with the hood and turns with the turret), efficiency 0.49, flywheels of 0.05 m radius (BIOBUZZ's 3 in wheels: 0.0381 m). A piece leaves when it is at the end of the path, the first conveyor motor feeds forward at 20 rad/s or more, and the flywheels spin. Exit velocity = chassis velocity + launch direction x efficiency x the flywheels' average rim speed; the flywheels pay the piece's kinetic energy, so a shot slows them. No spin, no air drag. |
| Sensors, camera | The Pinpoint, the IMU, the Limelight and the OctoQuad are found by the driver class the code asks for, under any name ([adding a device](../architecture/adding-a-device.md)): goBILDA Pinpoint ([page](../devices/pinpoint.md)), Control Hub IMU ([page](../devices/bno055-imu.md)), Limelight ([page](../devices/limelight.md)), and on BIOBUZZ's robot, whose turret has encoders, the OctoQuad ([page](../devices/octoquad.md)). REV colour sensors are found by the name the profile declares for each (below; [page](../devices/color-sensor.md)). |

**Unsupported and unmapped devices.** A device the robot has no model for (an external IMU, a colour sensor under a name the profile does not declare, a webcam, an OctoQuad on Hornet's robot) is unsupported and listed among the session's problems. A motor, servo or pin the profile does not name gets a free port, appears in `SessionStatus.unmapped`, and the problems list says the name is not on the robot. An unmapped servo accepts commands and does nothing; an unmapped digital pin reads high (hub pull-up); an unmapped analog pin reads 0 V.

## The profile

`RobotMapping` is the shape of a profile. Its `problems()` says what would stop the simulator building a robot from one (the builder's invariants: motor names listed once, at most 8 motors on the two hubs, four wheels or none and one motor per wheel, at most one driver for the turret, one servo per role and per name, at most two colour sensors); the two profiles have none, and `RobotProfilesTest` checks it.

| Field | Meaning |
|---|---|
| `name` | The robot's name. |
| `motors` | Each motor by the name the code gives it: slot, catalogue model, mirrored, ratio. |
| `massKg`, `wheelDiameterMm` | Mass and drive wheel diameter. |
| `driveMotor`, `driveRatio` | Catalogue motor on all four wheels and the motor turns to one turn of a wheel (the same for all four). |
| `turretRatio` | BIOBUZZ only: servo turns per turret turn. |
| `hubLogo`, `hubUsb` | Which way the Control Hub's logo and USB ports face (`UP`, `DOWN`, `FORWARD`, `BACKWARD`, `LEFT`, `RIGHT`), at right angles. |
| `camera` | Limelight position (X forward, Y left, Z up, metres from the robot's centre) and yaw, pitch, roll in degrees. |
| `turretParts` | The turret's servo, its OctoQuad encoders, its axis, whether the Limelight turns with it, and the positional servos (hood, stopper, colibri arms). |
| `colorSensors` | The REV Color Sensors by name and mount. |

### Hornet's profile (DECODE)

From the numbers Hornet was fitted on ([hornet-fit.md](hornet-fit.md)): name `Hornet 2026 (estimated)`, 11.3 kg, 96 mm wheels, `goBILDA-bare-6000` at 13.7:1 on the wheels (the 6000 rpm Yellow Jacket behind 13.7 is the 435 rpm motor), hub `UP`/`FORWARD`, Limelight at 0.16, 0, 0.25 m with pitch 20 degrees.

| Name in the code | Slot | Mirrored | Ratio |
|---|---|---|---|
| `frontLeft`, `backLeft` | wheels | yes | 13.7 (the drive ratio) |
| `frontRight`, `backRight` | wheels | no | 13.7 |
| `shooterLeft`, `shooterRight` | `SHOOTER` | no | 1 |
| `intake` | `INTAKE` | no | 13.7 |
| `upMotor` | `CONVEYOR` | yes | 13.7 |

One colour sensor, `upSensor`, on the Control Hub's I2C bus 2, 0.12 m behind the centre, 0.09 m left, 0.26 m up, looking right (the end of the conveyor). It is an estimate.

### BIOBUZZ's profile

From the team's table of its mechanisms and its CAD (the part is the simulator's model of it, `RobotDesign`, `MotorCatalog`, `ServoCatalog`). Name `BIOBUZZ`, 112 mm wheels, hub `UP`/`FORWARD`, Limelight on the turret at `RobotDesign.BIOBUZZ_CAMERA` (0.217, 0.0035, 0.256 m, pitch 45 degrees).

| Mechanism | Motor or servo | Ratio | In the simulator |
|---|---|---|---|
| Chassis (4: `lf`, `lr` mirrored, `rf`, `rr`) | SWYFT Spike 550 motor | 12.7:1 motor to mecanum | `swyft-spike-6000`, `driveRatio` 12.7: 6000 rpm, 1.47 kg cm and 9.2 A at stall; wheels 112 mm |
| Intake (`intake`) and indexer (`indexer`) | goBILDA Yellow Jacket, 6000 rpm | 2.5:1 | `goBILDA-bare-6000`, ratio 2.5 |
| Shooter (`shooterLeft`, `shooterRight`) | goBILDA Yellow Jacket, 6000 rpm | 43/30 = 1.4333 to the main roller | `goBILDA-bare-6000`, ratio 43/30; a ball leaves at 0.49 of the rim speed of a 3 in wheel (`BIOBUZZ_FLYWHEEL_RADIUS_M`), which is the motor's speed times 0.0381 m over the ratio |
| Turret (`turret`) | Axon Mini servo | 1.0185 servo to turret | `axon-mini-plus` (the Mini and Mini+ have the same speeds: 0.110 s per 60 degrees at 4.8 V, 0.090 s at 6 V); `turretRatio` 1.0185 |
| Colibri arms (`colibriLeft`, `colibriRight`) | goBILDA Torque servo, 5 turns | 40/30 servo to shaft | `gobilda-2000-torque-5turn`, `COLIBRI_RATIO` |
| Hood (`hood`) | goBILDA Super Speed servo, 300 degrees | 365/72 = 5.0694 servo to hood | `gobilda-2000-superspeed`, `HOOD_RATIO` |
| Stopper (`brake`) | goBILDA Torque servo, 5 turns | 1:1 | `gobilda-2000-torque-5turn`; it holds the balls at the foot of the column while shut (below) |
| Turret encoders | REV Through Bore Encoders on the OctoQuad | 5.5 (32T, channel 5), 44/7 = 6.2857 (28T, channel 4) turns per turret turn | `rev-tbe-v1` |
| Turret axis | | | x 0.045 m, y 0 (team code) |

**Colour sensors** (REV Color Sensor V3, two, found by name): `upperSensor` before the stopper, `lowerSensor` near the intake. The sensor sees one ball position along the path ([biobuzz-cad](biobuzz-cad.md): the stopper is the gate at path point 4, and the queue is 0.0711 m apart, a POLLEN's diameter). Both are on the robot's left wall at y = +0.065 m looking right (azimuth -90 degrees), so a ball in front is 2.9 cm from the sensor's face (Hornet's is about 2.7 cm, where its code reads under 5 cm as "a ball").

| Name | Hub and bus | Mount (robot frame, m) | Reads a ball when |
|---|---|---|---|
| `upperSensor` | Control Hub, I2C bus 2 | (0.089, 0.065, 0.060) | a ball waits at the stopper (piece 1 of the queue) |
| `lowerSensor` | Expansion Hub, I2C bus 0 | (0.187, 0.065, -0.112) | four balls are queued, so the robot is full (piece 4) |

At the start the four preloads wait at the stopper, so both read a ball; after four shots both read nothing. **These mounts are estimates**: the CAD has no sensors. Replace them with measured numbers when the real ones are mounted, in `RobotProfiles.biobuzz()` ([the colour sensor page](../devices/color-sensor.md)). The Control Hub's four I2C buses are taken (IMU 0, Pinpoint 1, first colour sensor 2, OctoQuad 3), which is why the second sensor is on the Expansion Hub.

## Motors

| Rule | Detail |
|---|---|
| Slots | `FRONT_LEFT`, `FRONT_RIGHT`, `REAR_LEFT`, `REAR_RIGHT` (one motor each; all four or none), `INTAKE` (the roller), `CONVEYOR`, `SHOOTER` (a flywheel), `TURRET` (a motor turret; BIOBUZZ's is a servo). A motor has one slot; intake, conveyor and shooter take any number. |
| Catalogue | `MotorCatalog`: `goBILDA-bare-6000` (the goBILDA Yellow Jacket's winding with no gearbox, from the measured 435 rpm motor Hornet was fitted on) and `swyft-spike-6000` (SWYFT Spike 550, published numbers at 12 V: free speed, stall torque, stall current, encoder counts), each with a ratio on top; the rotor's inertia is an estimate. A roller's inertia and friction scale with its ratio. |
| Ratio | Motor turns to one turn of the part it drives (the reduction between them; 2 for 2:1, 43/30 for a belt). It is all the simulator knows of the gearing: a roller's surface speed is the motor's speed times its radius over the ratio, a flywheel's rim the same, and the window turns the picture's rollers by it. The encoder counts the motor's turns, as a real one does. A wheel's is `driveRatio` (the motor's torque goes up and its speed down by it, and the encoder counts it times as many for a turn of the wheel). A turret's is `turretRatio`. |
| Mirrored | The motor is mounted the other way round, so positive power turns its part backwards. Hornet inverts `frontLeft`, `backLeft` (Pedro's `REVERSE`) and `upMotor` (`setInverted(true)`) and nothing else. On a wheel it is the motor's direction. An unmirrored conveyor feeds the shooter turning forward, a mirrored one backward (Hornet's belt is mirrored). Shooter wheels that get the same power are all unmirrored; if one is mirrored and the other not, one spins against the other, the shot falls short and the session reports a problem. |
| Ports | Two hubs of four ports. Wheels take Control Hub ports 0 to 3 (front left 0, rear left 1, front right 2, rear right 3); the others take the Expansion Hub first, then the Control Hub's free ports. Code finds motors by name, so ports are never asked. |
| Turret | A ratio of 3.5 means 3.5 motor (or servo) turns per turret turn; no mechanical limit; zero is forward at session start. The launch direction turns about the robot's Z axis (positive left) by `sign * shaftAngle / ratio` (`PlantView.shaftAngleRad`, radians since the session began; `sign` -1 if mirrored). On DECODE's generic path only the direction turns and the piece leaves from the end of the path; on BIOBUZZ's the stretch of the path inside the turret and the opening the shot leaves from turn with it ([biobuzz-cad](biobuzz-cad.md)). |

**The turret has three ways to be verified.** A motor turret by a fixture only (`MechanismsSessionTest`, the `Turret Aim` OpMode: a P loop on a "turret" motor; a preloaded piece leaves along the held heading, 0, +45, -90 degrees, -45 with a mirrored motor). A servo turret by `MechanismsSessionTest` with the `Turret Servo` fixture. The whole BIOBUZZ robot by `BiobuzzStubSessionTest` ([biobuzz-stub](biobuzz-stub.md)). Hornet's code is DECODE's, so no team program of the game exists yet.

### A turret turned by a continuous-rotation servo

BIOBUZZ's turret is turned by a continuous-rotation servo (Axon Mini+) on the Control Hub's servo port 0, 1.0185 turns of servo to one of the turret. The servo is a `CRServo` in the code, found by the name `turret`. A turret has a motor or a servo, not both.

| Part | Model |
|---|---|
| Command | The pulse the hub sends: 1500 microseconds is stopped and 1000 or 2000 is full speed either way, clipped beyond 500 microseconds. The SDK's `setPower(1)` sends 2400 (it spans 600 to 2400 for a continuous-rotation servo), which is 0.9 of full speed here, and the simulator does not know where the real servo saturates. A dead band of 2 microseconds around the middle. |
| Speed | The servo's no-load speed at the 5 V of a hub's servo port (`ServoCatalog`): `axon-mini-plus` 9.8 rad/s (an estimate between the datasheet's 0.110 s per 60 degrees at 4.8 V and 0.090 s at 6 V), `gobilda-2000-superspeed` 19.8 rad/s (0.055 s and 0.043 s), `gobilda-2000-torque-5turn` 4.4 rad/s. |
| Lag | 30 milliseconds, first order, an estimate. Torque, load and the servo's own position feedback are not modelled: the servo is a speed. |
| Direction | Positive power turns the turret left (counter-clockwise from above), a mirrored servo the other way. |

### Encoders and the OctoQuad

BIOBUZZ's turret carries two V1 REV Through Bore Encoders, on OctoQuad ports 5 (the 32-tooth gear's, 5.5 turns per turret turn) and 4 (the 28-tooth gear's, 44/7 = 6.285714). An encoder has a model (`rev-tbe-v2`, absolute pulse 3.884 to 998.06 microseconds, or `rev-tbe-v1`, 1 to 1024), turns of the encoder per turn of the turret, a zero offset (where it reads zero with the turret forward, in degrees of the encoder) and a mirrored flag (the real encoder's direction is not known from its datasheet, so check it on the robot). Any encoder puts an OctoQuad on the robot; code finds it by the class `OctoQuad`, under any name. Details and limits: [octoquad](../devices/octoquad.md).

**Ambiguity.** An absolute encoder repeats every turn of itself, so two encoders whose ratios are not whole numbers tell the turret's angle only within one turn of the turret (the vernier the team's code computes). Past one full turn of the turret the encoders show what the real ones would, which is not the first turn's reading again. The turret has no mechanical limit in the simulator; a real one has cables.

### Positional servos: the hood, the stopper and the colibri arms

BIOBUZZ has four more servos that go to a position (`servo.setPosition`): the hood is a Super Speed (300 degrees) and the stopper and the colibri arms are Torque servos with 5 turns. They take Control Hub servo ports after the turret servo's (hood 1, stopper 2, left colibri 3, right 4). `PositionServoPlant`: the SDK's 600 to 2400 microseconds are positions 0 to 1, the shaft travels 300 degrees (1800 for the 5-turn model) at the servo's top speed (19.8 rad/s for the Super Speed, 4.4 for the 5-turn model, so a whole 5-turn range takes 7 s), holds when the hub stops pulsing, and a mirrored servo has its travel the other way round and starts at its far end.

| Part | What it does in the simulation |
|---|---|
| Hood | Sets the launch elevation: 45 degrees at the servo's 0 plus the hood's angle, which is the servo's angle over 5.0694 (365:72 gears, the team's table), up to the hood's stop at **44 degrees**: 45 degrees at position 0, 89 from position 0.7435 of the Super Speed servo on (`HOOD_RANGE_RADIANS`). The CAD allows only 1 to 25 degrees; the stop is widened so that a HIVE's CELL, which is open only at the end of its arm, can be reached with a steep lob from near and from far ([biobuzz-stub](biobuzz-stub.md)). A robot with no hood servo launches at its fixed 55 degrees |
| Stopper | Holds the piece at the end of the indexer: no piece leaves until its servo has turned 60 degrees (position 0.0333 of its 1800 degrees), whatever the belt and flywheels do. A robot with no stopper servo has nothing holding it |
| Colibri arms | Moved in the picture only. They change nothing in the simulation |

All four are estimates where the CAD has no number ([biobuzz-cad](biobuzz-cad.md)). Verified by `PositionServoPlantTest`, `MechanismsSessionTest` (the `Hood Stopper` fixture: closed stopper holds, open releases, the hood changes the elevation by 9.9 degrees) and `BiobuzzStubSessionTest`.

### The picture

`RobotInfo.model` is `biobuzz` for a robot with a turret, and the window draws it from the CAD ([biobuzz-cad](biobuzz-cad.md)): the wheels, intake, indexer, flywheel, hood, stopper, colibri arms, encoder gears and turret each move with their motor or servo.

### The Limelight on the turret

BIOBUZZ's Limelight mount is given with the turret facing forward and the camera turns with the turret about the turret's axis (metres from the robot's centre, X forward, Y left), so what it sees, and the tag bearings it reports, follow the turret. The window draws the turret as its own body (`BodyKinds.TURRET`).
