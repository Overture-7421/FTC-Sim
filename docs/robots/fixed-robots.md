# The fixed robots and the mapping

A team does not describe its robot to FTC-Sim; it tells the simulator which motor of its code is which part of the robot the
game fixes. The robot of each game, its size, its mechanisms and the physics of its parts, is designed once, in
`dev.ftcsim.simulator.robot.RobotDesign`, from the numbers of the robot the simulator was fitted to (`hornet-profile.md`).
What a student supplies is a small **mapping**, `dev.ftcsim.contracts.session.RobotMapping`, kept in `ftcsim.json` at the
root of their project. The worker never reads `ftcsim.json`: it receives the mapping inline in the session request (link
protocol 7, `architecture/link.md`) and builds the robot from the design of the request's field. `RobotDesign.spec(mapping)`
returns the `RobotSpec` (hubs and ports, chassis, mechanism specs, sensor places) that `RobotRig` and `RobotSpec.mechanisms`
consume.

## The fixed robots

| Game | Robot |
|---|---|
| DECODE | Four mecanum wheels, an intake roller, a conveyor and a shooter of one or more flywheels. No turret. |
| BIOBUZZ | The same robot with a **turret** motor that rotates the launcher about the robot's vertical axis. |

Any other field (the bare floor included) gets DECODE's robot. A part exists only when the mapping gives it a motor: no intake
motor, no intake. The numbers are `RobotDesign`'s constants (Javadoc there; `RobotDesignTest` spells them out one by one, so a
change of a fitted number fails a test); they are Hornet's fitted numbers (`hornet-profile.md`):

- **Chassis**: the generic 18 inch mecanum chassis (0.36 m wheelbase and track) with its fitted wheel damping, friction and
  roller numbers. Mass and wheel diameter come from the mapping.
- **Hubs**: a Control Hub (address 173, embedded, the parent) and an Expansion Hub (address 2).
- **Storage**: one path of three points that holds three pieces; the intake and the conveyor move pieces along it. A session
  starts, and a world reset restarts, with it full: the field says what the robot starts holding (`FieldRun.robotPreloads`),
  DECODE two purple artifacts and a green one, BIOBUZZ three of the robot's four preload POLLEN.
- **Intake and conveyor**: the intake is a zone in front of the robot (DECODE 0.10 to 0.15 m across, BIOBUZZ 0.06 to 0.085 m)
  that takes in the game's pieces while the roller turns in at 10 rad/s or more; the roller (0.025 m radius) drives the first
  0.20 m of the path and the conveyor (0.02 m radius) the path from 0.15 m on. Each mapped part has a rotor load and friction
  of its own (`RobotDesign`).
- **Shooter**: a launcher 55 degrees up and forward, with an efficiency of 0.49 and flywheels of 0.05 m radius. A piece leaves
  when it is at the end of the path, the first conveyor motor feeds forward at 20 rad/s or more, and the flywheels spin. It
  leaves with the chassis's velocity plus the launch direction times the efficiency times the average rim speed of the
  flywheels, which pay the piece's kinetic energy, so a shot slows them down. No spin, no air drag.
- **Sensors and camera**, all fixed and ideal, found by the driver class robot code asks for, under any name
  (`architecture/adding-a-device.md`): the goBILDA Pinpoint (`devices/pinpoint.md`), the REV colour sensor
  (`devices/color-sensor.md`), the Control Hub's IMU (`devices/bno055-imu.md`) and the Limelight (`devices/limelight.md`).
  Only the hub mounting and the Limelight mount are the student's. Anything else a program asks for (an OctoQuad, an external
  IMU, a second sensor of a kind, a webcam) is unsupported and listed among the session's problems.
- **Servos and pins** are not mapped. An unmapped servo accepts commands and has no effect; an unmapped digital pin reads high
  (through the hub's pull-up) and an analog pin 0 V. Each name gets a free port and is reported in `SessionStatus.unmapped`,
  and the session's problems say that the name is not in the mapping.

## The mapping

| Part of `RobotMapping` | Meaning |
|---|---|
| `name` | The robot's name. |
| `motors` | Each motor by the name the code gives it, with its **slot**, its **model** and **Mirrored**. At most 8. |
| `massKg` | Mass, 1 to 40 kg. |
| `wheelDiameterMm` | The drive wheels' diameter, 40 to 200 mm. |
| `driveMotor` | The catalogue motor on all four wheels (`MotorCatalog`, `Catalogue.motors()`). |
| `turretRatio` | BIOBUZZ only: motor revolutions for one turn of the turret, above 0. |
| `hubLogo`, `hubUsb` | Which way the Control Hub's logo and USB ports face on the robot (`UP`, `DOWN`, `FORWARD`, `BACKWARD`, `LEFT`, `RIGHT`), at right angles. |
| `camera` | Where the Limelight is: X forward, Y left, Z up in metres from the robot's centre, and yaw, pitch and roll in degrees. |

`RobotMapping.problems(turret)` is the one place that says what is wrong with a mapping; the Robot page shows its sentences
and the worker refuses a request whose mapping has any.

**Slots.** `FRONT_LEFT`, `FRONT_RIGHT`, `REAR_LEFT`, `REAR_RIGHT` (a wheel holds one motor, and the four are all or none),
`INTAKE` (the roller), `CONVEYOR`, `SHOOTER` (a flywheel; Hornet has two) and `TURRET` (BIOBUZZ only). A motor is in one
slot; the intake, conveyor and shooter take any number.

**Model.** A catalogue motor id. Empty is the part's usual motor: the flywheels turn on the bare motor, everything else on
the 435 rpm one (`RobotMapping.usualModel`). The wheels all use `driveMotor` and their own model stays empty.

**Mirrored.** The motor is mounted the other way round, so positive power turns its part backwards. On a wheel it is the
motor's direction; on the conveyor and the shooter the sense in which the part drives, and the conveyor's feed condition
follows (an unmirrored conveyor feeds the shooter when it turns forward, a mirrored one the other way; Hornet's belt is
mirrored); a mirrored intake roller takes pieces in while it turns the other way. It follows what the code does: Hornet
inverts `frontLeft`, `backLeft` (Pedro's `REVERSE`) and `upMotor` (`setInverted(true)`) and nothing else, so its two shooter
motors, which get the same power, are not mirrored. If one shooter wheel is mirrored and the other is not, one spins against
the other, the shot loses speed and falls short; the session reports it as a problem.

**The 8-motor limit.** Two hubs of four ports. Wheels take the Control Hub's ports 0 to 3 (front left 0, rear left 1, front
right 2, rear right 3), the other motors the Expansion Hub first and then what is left of the Control Hub's; code finds motors
by name, so ports are never asked. A ninth motor is a problem.

**Turret ratio and aiming.** A ratio of 3.5 means 3.5 turns of the motor for one turn of the turret; there is no mechanical
limit and zero is forward at the start of a session. The launch direction is turned about the robot's Z axis (positive to the
left) by `sign * shaftAngle / ratio`: the turret motor's shaft angle in radians since the session began (positive as its encoder counts
up; `PlantView.shaftAngleRad`) and `sign` -1 for a mirrored motor. The first turret motor mapped aims. Only the direction
turns: the piece still leaves from the end of the path.

**The turret is validated by a fixture only.** Hornet's code is DECODE's, so no real team program exercises the turret.
`MechanismsSessionTest` (`acceptance-tests`) runs the `Turret Aim` fixture OpMode (a P loop on a "turret" motor) on a BIOBUZZ
session and asserts that a preloaded piece leaves along the heading the turret was held at (0, +45 and -90 degrees, and a
mirrored motor). That is all the validation there is.

## `ftcsim.json`, schema 2

The file is FTC-Sim's own and is read and written whole by the application (`ProjectMappingsImpl`). Every key but `schema` is
optional and takes the usual value of `RobotMapping.empty`, the robot's name being the project's. A key that is not listed, or
a value of the wrong type, makes the file unreadable.

```json
{
  "schema": 2,
  "name": "Hornet 2026",
  "motors": {
    "frontLeft": { "slot": "FRONT_LEFT", "model": "", "mirrored": true },
    "shooterLeft": { "slot": "SHOOTER", "model": "goBILDA-5203-1150", "mirrored": false }
  },
  "massKg": 11.3,
  "wheelDiameterMm": 96,
  "driveMotor": "goBILDA-5203-435",
  "turretRatio": 1,
  "hub": { "logo": "UP", "usb": "FORWARD" },
  "camera": { "x": 0.16, "y": 0, "z": 0.25, "yawDegrees": 0, "pitchDegrees": 20, "rollDegrees": 0 },
  "run": { "field": "decode", "alliance": "BLUE", "startPose": "BLUE goal side" },
  "startPositions": [
    { "name": "Near goal", "field": "decode", "x": -1.2, "y": 0.5, "headingDegrees": 135 }
  ]
}
```

The order of `motors` is the order they were mapped. `run` is how a run starts when nobody chose otherwise (the Simulator's
Setup tab writes it with Save as default). `startPositions` are the start positions the team named, for a field each (metres
in the field's frame, degrees from +X): they are in the project, so every teammate has them in the Simulator's start position
menu, where **Edit positions...** adds, changes and removes them. Names are unique within a field, ignoring case; every key of
a position is required. Saving the robot, the run or the positions keeps the other two parts.

**Compatibility.** A file of schema 1 (the old robot profile) is not migrated and not overwritten silently: the window lists
"ftcsim.json is from an older version" as a problem, treats the project as having no mapping, and the next save copies the
file to `ftcsim.old.json` next to it. A file that does not parse is treated the same way, with what could not be read. When
the project's folder cannot be written to, FTC-Sim keeps the file in `%LOCALAPPDATA%\FTC-Sim\profiles` and says so.

## Making one

Open the project, pick an OpMode on the **Robot** screen and drag each motor it asks for onto its part (`architecture/ui.md`,
"Robot"). **Create** writes the file, **Save** writes it again after a change, and the code restarts with it.
