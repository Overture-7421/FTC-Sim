# The fixed robots and the mapping

This page says which robot FTC-Sim simulates and what a team writes in `ftcsim.json` to connect its code to it.

**The robot designs are fixed, and sensors are fixed and ideal.** A team supplies only a mapping: which motor of its code is which part, the motor model, Mirrored, a few body numbers, the hub mounting and the Limelight mount. There are no editable zones, mechanisms or sensor settings. `dev.ftcsim.simulator.robot.RobotDesign` holds the designs (numbers from the robot the simulator was fitted to: [hornet-fit.md](hornet-fit.md)); `dev.ftcsim.contracts.session.RobotMapping` is the mapping. The worker never reads `ftcsim.json`: it gets the mapping inline in the session request ([link](../architecture/link.md), protocol 7) and builds the robot with `RobotDesign.spec(mapping)`.

## The designs

| Game | Robot |
|---|---|
| DECODE | Four mecanum wheels, an intake roller, a conveyor, a shooter of one or more flywheels. No turret. |
| BIOBUZZ | The same robot plus a **turret** motor that turns the launcher about the vertical axis. |

Any other field (the bare floor too) gets DECODE's robot. **A part exists only when the mapping gives it a motor.** Each mapped part has its own rotor load and friction (`RobotDesign`); `RobotDesignTest` spells out every constant, so changing a fitted number fails a test.

| Part | Design |
|---|---|
| Chassis | Generic 18 inch mecanum (0.36 m wheelbase and track) with the fitted losses; mass and wheel diameter come from the mapping. Without all four wheels mapped there is no drivetrain. |
| Hubs | Control Hub (address 173, embedded) and Expansion Hub (address 2). |
| Storage | One path of three points holding three pieces. A session or world reset starts it full: DECODE two purple and a green artifact, BIOBUZZ three POLLEN (`FieldRun.robotPreloads`). |
| Intake, conveyor | The intake takes in the game's pieces (DECODE 0.10 to 0.15 m across, BIOBUZZ 0.06 to 0.085 m) in a zone in front while its roller turns in at 10 rad/s or more. The roller (radius 0.025 m) drives the first 0.20 m of the path; the conveyor (radius 0.02 m) drives from 0.15 m on. |
| Shooter | Launcher 55 degrees up and forward, efficiency 0.49, flywheels of 0.05 m radius. A piece leaves when it is at the end of the path, the first conveyor motor feeds forward at 20 rad/s or more, and the flywheels spin. Exit velocity = chassis velocity + launch direction x efficiency x the flywheels' average rim speed; the flywheels pay the piece's kinetic energy, so a shot slows them. No spin, no air drag. |
| Sensors, camera | Found by the driver class the code asks for, under any name ([adding a device](../architecture/adding-a-device.md)): goBILDA Pinpoint ([page](../devices/pinpoint.md)), REV colour sensor ([page](../devices/color-sensor.md)), Control Hub IMU ([page](../devices/bno055-imu.md)), Limelight ([page](../devices/limelight.md)). |

**Unsupported and unmapped devices.** A device the design has no model for (an OctoQuad, an external IMU, a second sensor of a kind, a webcam) is unsupported and listed among the session's problems. A motor, servo or pin missing from the mapping gets a free port, appears in `SessionStatus.unmapped`, and the problems list says the name is not in the mapping. An unmapped servo accepts commands and does nothing; an unmapped digital pin reads high (hub pull-up); an unmapped analog pin reads 0 V.

## The mapping

`RobotMapping.problems(turret)` is the one place that says what is wrong with a mapping; the Robot screen shows its sentences and the worker refuses a request whose mapping has any.

| Field | Meaning | Default |
|---|---|---|
| `name` | The robot's name. | Project folder name |
| `motors` | Each motor by the name the code gives it: slot, model, Mirrored. At most 8. | none |
| `massKg` | Mass, 1 to 40 kg. | 11.3 |
| `wheelDiameterMm` | Drive wheel diameter, 40 to 200 mm. | 96 |
| `driveMotor` | Catalogue motor on all four wheels (`MotorCatalog`, `Catalogue.motors()`). | `goBILDA-5203-435` |
| `turretRatio` | BIOBUZZ only: motor turns per turret turn, above 0. | 1 |
| `hubLogo`, `hubUsb` | Which way the Control Hub's logo and USB ports face (`UP`, `DOWN`, `FORWARD`, `BACKWARD`, `LEFT`, `RIGHT`), at right angles. | `UP`, `FORWARD` |
| `camera` | Limelight position (X forward, Y left, Z up, metres from the robot's centre) and yaw, pitch, roll in degrees. | 0.16, 0, 0.25; 0, 20, 0 |

| Motor rule | Detail |
|---|---|
| Slots | `FRONT_LEFT`, `FRONT_RIGHT`, `REAR_LEFT`, `REAR_RIGHT` (one motor each; all four or none), `INTAKE` (the roller), `CONVEYOR`, `SHOOTER` (a flywheel; Hornet has two), `TURRET` (BIOBUZZ only). A motor has one slot; intake, conveyor and shooter take any number. |
| Model | A catalogue motor id. Empty means the part's usual motor (`RobotMapping.usualModel`): the bare motor (`goBILDA-bare-6000`) for flywheels, the 435 rpm one otherwise. Wheels use `driveMotor`; their own model stays empty. |
| Mirrored | The motor is mounted the other way round, so positive power turns its part backwards. Set it as the code does: Hornet inverts `frontLeft`, `backLeft` (Pedro's `REVERSE`) and `upMotor` (`setInverted(true)`) and nothing else. On a wheel it is the motor's direction. An unmirrored conveyor feeds the shooter turning forward, a mirrored one backward (Hornet's belt is mirrored). A mirrored intake roller takes pieces in turning backward. Shooter wheels that get the same power are all unmirrored; if one is mirrored and the other not, one spins against the other, the shot falls short and the session reports a problem. |
| Ports | Two hubs of four ports, so a ninth motor is a problem. Wheels take Control Hub ports 0 to 3 (front left 0, rear left 1, front right 2, rear right 3); the others take the Expansion Hub first, then the Control Hub's free ports. Code finds motors by name, so ports are never asked. |
| Turret | Ratio 3.5 means 3.5 motor turns per turret turn; no mechanical limit; zero is forward at session start. The launch direction turns about the robot's Z axis (positive left) by `sign * shaftAngle / ratio` (`PlantView.shaftAngleRad`, radians since the session began; `sign` -1 if mirrored). The first mapped turret motor aims. Only the direction turns: the piece still leaves from the end of the path. |

**The turret is validated by a fixture only.** Hornet's code is DECODE's, so no team program uses it. `MechanismsSessionTest` (`acceptance-tests`) runs the `Turret Aim` fixture OpMode (a P loop on a "turret" motor) on a BIOBUZZ session and asserts that a preloaded piece leaves along the held heading (0, +45, -90 degrees; -45 with a mirrored motor).

## `ftcsim.json`, schema 2

The application reads and writes the file whole (`ProjectMappingsImpl`). Every key but `schema` is optional and takes the default above; an unlisted key or a value of the wrong type makes the file unreadable.

```json
{ "schema": 2, "name": "Hornet 2026",
  "motors": { "frontLeft": { "slot": "FRONT_LEFT", "model": "", "mirrored": true },
              "shooterLeft": { "slot": "SHOOTER", "model": "goBILDA-5203-1150", "mirrored": false } },
  "massKg": 11.3, "wheelDiameterMm": 96, "driveMotor": "goBILDA-5203-435", "turretRatio": 1,
  "hub": { "logo": "UP", "usb": "FORWARD" },
  "camera": { "x": 0.16, "y": 0, "z": 0.25, "yawDegrees": 0, "pitchDegrees": 20, "rollDegrees": 0 },
  "run": { "field": "decode", "alliance": "BLUE", "startPose": "BLUE goal side" },
  "startPositions": [ { "name": "Near goal", "field": "decode", "x": -1.2, "y": 0.5, "headingDegrees": 135 } ] }
```

- `motors` keeps the order the motors were mapped.
- `run` is how a run starts when nobody chose otherwise (the Setup tab's Save as default).
- `startPositions` are named per field (metres in the field's frame, degrees from +X). They live in the project, so every teammate sees them in the start position menu, where **Edit positions...** adds, changes and removes them. Names are unique per field, ignoring case; every key of a position is required. Saving the robot, the run or the positions keeps the other two.
- **Compatibility:** a file of schema 1 (the old robot profile) or a newer schema, a file without a schema number, and a file that does not parse are not used and never overwritten silently. The window lists the problem ("ftcsim.json is from an older version", "...from a newer version", or what could not be read) and treats the project as having no mapping; the next save copies the file to `ftcsim.old.json`. If the project folder is not writable, FTC-Sim keeps the file in `%LOCALAPPDATA%\FTC-Sim\profiles` and says so.

To make one, open the project, pick an OpMode on the **Robot** screen and drag each motor it asks for onto its part ([UI](../architecture/ui.md)). **Create** writes the file, **Save** writes it again, and the code restarts with it.
