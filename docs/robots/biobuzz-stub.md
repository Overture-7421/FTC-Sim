# The BIOBUZZ stub program

What the stand-in program for the BIOBUZZ robot proves and how to run it in the window; read it before trusting a BIOBUZZ robot you have not yet written code for.

There is no BIOBUZZ robot program yet. `BiobuzzStubTeleOp` (`acceptance-tests\src\scenarios\java\dev\ftcsim\scenarios\opmodes`) is a short TeleOp written as a team writes one, knowing nothing of the simulator: mecanum drive on the sticks, a goBILDA Pinpoint called `odopods`, a Limelight called `limelight` (it rides on the turret), a continuous-rotation `CRServo` called `turretServo` that turns the turret, an `OctoQuad` called `octoquad` that reads the turret's two absolute encoders, and the intake (`intake`), indexer (`up`), flywheels (`shooterLeft`, `shooterRight`) and four positional servos (`hood`, `stopper`, `colibriLeft`, `colibriRight`). It shows what it learns on telemetry. `BiobuzzStubSessionTest` (`acceptance-tests`) runs it on the BIOBUZZ field and checks:

| Check | How |
|---|---|
| The robot drives as the sticks say | Forward moves ahead along the heading, left strafes to the robot's left, right stick turns clockwise (true chassis pose, red wall start) |
| The odopods work | The Pinpoint's X, Y and heading equal the true motion in the robot's start frame (within 15 mm and 3 degrees), and its pod X count follows the travel at the 4-bar pod resolution the stub sets |
| The Limelight sees the HIVE tags | Standing 1 m from tag 31 of the red HIVE, tags 30 to 33 are listed, tag 31 among them, `botpose` has no tags, and turning the robot 12 degrees to the left moves the target to the right in the image (`tx` rises by more than 6 degrees) |
| The turret turns and is read back | The bumpers turn the servo (left bumper turns the turret left); the stub decodes the turret angle from the two absolute encoders on the OctoQuad (5.5 and 44/7 turns to one of the turret, the vernier of the two) and it matches the true angle within a degree from -165 to +250 degrees. The window's turret body is turned by the same angle |
| Every mechanism of the picture moves | The wheels turn forward when driving; the left trigger runs the intake, the right trigger the flywheel, B the indexer; X opens the stopper to 150 degrees of servo, Y raises the colibri arms 90 degrees, the D-pad raises the hood and the encoder gear turns 5.5 times to the turret's one (`everyMovingPartOfThePictureFollowsTheMotorOrServoThatMovesIt`) |
| The pose estimate | `BiobuzzPoseEstimator` keeps the Pinpoint's odometry in the field frame and pulls it towards the pose the Limelight's 3D targets give (the share of Hornet's `Camera`: 0.6 position and heading when the tags are within 3 m, 0.4 and 0.9 beyond). Standing 1 m from tag 31 it has the true pose within 5 cm and 3 degrees after a quarter of a second and keeps it driving away (`theStubFindsWhereItIsOnTheFieldFromTheTagsAndKeepsTrackOfItByTheOdometryWhenItDrivesAway`); facing the wall it has none. Its maths has its own tests (`BiobuzzPoseEstimatorTest`): placement from two tags at any turret angle, the blends, one tag, rejection of tags that do not fit |
| The turret aims at the blue CELL | The first stage of the automatic shot (below) turns the turret to face the middle of the blue HIVE's CELL that is nearer to the robot (the side of the HIVE with its arm tipped towards the robot), from the estimated pose; the turret settles within 4 degrees of the true bearing and the encoders agree (`holdingATurnsTheTurretToTheBlueCellNearerTheRobotFromTheEstimatedPoseAndTheEncodersAgree`) |
| A shoots, automatically | One press of A (`K`) starts `BiobuzzAutoShoot`: the turret turns to the blue HIVE's nearer CELL from the estimated pose, the flywheels go to the speed that reaches it (ballistics with the shooter's tuning numbers: the speed a ball leaves with is 0.49 of the rim speed, the launch angle 45 degrees plus the hood), the hood goes to the angle that needs the least flywheel speed (about 69 degrees up for a short shot, lower for a longer one; its servo has five turns, so the code keeps to positions up to 0.41, the hood's 24 degrees), and when the turret is within 2 degrees, the flywheels within 3 percent for 0.3 s, the hood's servo has had the time to turn (7 s for a whole range) and the robot is not turning, the stopper opens and the indexer feeds (pausing while the flywheels sag) for 2 s, then everything stops. Another press cancels; no pose for 8 s, or a CELL out of reach, ends it with a message on `AutoShoot`. `BiobuzzAutoShootTest` checks the ballistics against an independent flight and each step of the sequence; `pressingAAimsTheTurretSpinsTheFlywheelsAndShootsTheBallsHeldAtTheStopperIntoTheBlueCell` runs it on the field: the robot finds its place from the red HIVE's tags, backs away on the odometry, and one press sends a ball within 15 cm of the CELL's middle (it measured 2 cm) |
| The intake is by hand and the preloads wait at the stopper | The left trigger (`Z`) runs the intake and, slowly, the indexer, with the stopper shut: balls climb to the stopper and wait there. At the start the three preloads are already there (`pressingA...` checks it) |
| The Limelight turns with the turret | The estimate is right with the turret at any angle only if the camera's position follows the turret: the unit tests place the robot with the turret at 0, 1.2 and -2.5 radians, and the session tests aim the turret about 78 degrees off the front and still get the pose |

**What it cannot prove.** The Pinpoint is ideal ([pinpoint.md](../devices/pinpoint.md)): pod offsets and encoder directions are accepted and ignored, so a wrong offset in the real code does not show here. The offsets in the stub are the CAD's ([biobuzz-cad](biobuzz-cad.md)). The camera has no pose of its own on this field: every BIOBUZZ tag is on a HIVE, which tips, so `botpose` is empty ([limelight.md](../devices/limelight.md)); the stub's estimator therefore carries its own map of the tags, `BiobuzzHive`, with the HIVEs as they start a match. After a TIP the tags are up to 7 cm from the map: the estimate then has that error, and a fix that disagrees with the tags or with the estimate by a metre is ignored. The camera's frames are some tens of milliseconds old, so the stub leaves out the frames taken while the turret turns faster than 40 degrees a second or the robot faster than 60. The estimator, the HIVE map and the automatic shot are plain Java without the SDK, written for Java 8 (a robot project compiles with `-source 8`: no records, no `List.of`; `BiobuzzPoseEstimatorTest` compiles them with `--release 8`), and come with the stub: copy `BiobuzzPoseEstimator.java`, `BiobuzzHive.java` and `BiobuzzAutoShoot.java` with it.

## Running it in the window

1. Make a robot project for your SDK: clone FIRST's `FtcRobotController` at the release you use and open it as the project.
2. Copy `BiobuzzStubTeleOp.java`, `BiobuzzAutoShoot.java`, `BiobuzzPoseEstimator.java` and `BiobuzzHive.java` into `TeamCode`, and change their package lines.
3. Put an `ftcsim.json` at the project root, or map the motors, the turret servo, the four mechanism servos and the camera on the **Robot** screen ([fixed-robots.md](fixed-robots.md)). The names are the stub's. The turret's encoders and axis are not on the screen: they are in the file, and the screen keeps them when it saves:

```json
{ "schema": 2, "name": "BIOBUZZ stub", "turretRatio": 2.75, "wheelDiameterMm": 112, "driveMotor": "swyft-spike-12.7",
  "motors": { "frontLeft":  { "slot": "FRONT_LEFT",  "model": "", "mirrored": true },
              "backLeft":   { "slot": "REAR_LEFT",   "model": "", "mirrored": true },
              "frontRight": { "slot": "FRONT_RIGHT", "model": "", "mirrored": false },
              "backRight":  { "slot": "REAR_RIGHT",  "model": "", "mirrored": false },
              "intake":       { "slot": "INTAKE",   "model": "", "mirrored": false },
              "up":           { "slot": "CONVEYOR", "model": "", "mirrored": false },
              "shooterLeft":  { "slot": "SHOOTER",  "model": "", "mirrored": false },
              "shooterRight": { "slot": "SHOOTER",  "model": "", "mirrored": false } },
  "turret": { "servo": { "name": "turretServo" },
              "encoders": [ { "channel": 0, "turnsPerTurretTurn": 5.5 },
                            { "channel": 1, "turnsPerTurretTurn": 6.285714285714286 } ],
              "axis": { "x": 0.089, "y": 0 }, "cameraOnTurret": true,
              "hood": { "name": "hood" }, "stopper": { "name": "stopper" },
              "colibriLeft": { "name": "colibriLeft" }, "colibriRight": { "name": "colibriRight" } } }
```

Without the `turret` object the stub's lookup of the OctoQuad fails (the robot has none), and the servos are unmapped: they accept commands and do nothing, and the session lists them. The Limelight's mount (`camera`) is given with the turret facing forward. Left out, it is the place on the robot's 45 degree panel (see [biobuzz-cad](biobuzz-cad.md)), which the owner chose; the CAD has no Limelight.

A ready-made project, FIRST's `FtcRobotController` for the SDK of your choice with the stub and this file added, is the quickest way to try it: BIOBUZZ is an SDK 12 game, so use the `v12.0` tag.

4. **Open project**, pick BIOBUZZ for the game, choose the `BIOBUZZ Stub` OpMode, Init, wait for the Pinpoint to say `READY`, Start. Drive toward a HIVE until the telemetry shows an `Estimate` (the camera has seen two tags together), then press A (`K` on the keyboard): the turret turns to the blue CELL, the flywheels spin up and, when everything is ready, the balls go. `Aim` shows the target and the bearing and `AutoShoot` the step. Hold the left trigger (`Z`) to take balls in; they wait at the stopper.
