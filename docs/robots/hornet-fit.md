# How the built-in Hornet robot was fitted

This page lists the fitted drivetrain and shooter numbers of the fixed DECODE robot (`RobotDesign`, [fixed-robots.md](fixed-robots.md)), how each was obtained and how sure they are; read it before changing one.

`RobotDesignTest` spells out every constant, so a change to a fitted number fails a test and is made on purpose. The drivetrain is calibrated against the four constants Hornet's team measured with Pedro Pathing's tuners (`pedroPathing/Constants.java`), because Pedro predicts its braking distance from them and a robot that coasts or tops out differently makes autos overshoot.

## Drivetrain result

| Constant | Real robot | Simulated | Difference |
|---|--:|--:|--:|
| `xVelocity`, forward top speed, in/s | 76.84 | 76.84 | 0.0 % |
| `yVelocity`, sideways top speed, in/s | 58.91 | 58.69 | -0.4 % |
| `forwardZeroPowerAcceleration`, in/s^2 | -32.88 | -32.75 | -0.4 % |
| `lateralZeroPowerAcceleration`, in/s^2 | -72.06 | -73.03 | +1.3 % |

Measured by Pedro's own tuners on the simulated robot (`HornetBringupTest#pedrosOwnTunersMeasureTheSimulatedDrivetrain`, needs the Hornet package; it only reports and asserts nothing). The unfitted generic robot reached 86.33 and 84.29 in/s and slowed by only about 0.5 in/s^2 forward (it rolled on for 66 s and 13 m) and 1.54 in/s^2 sideways. `HornetDrivetrainCalibrationTest` (no Hornet package) repeats the four measurements on the chassis and keeps all four within 5 %. It reads the exact speed and floats the motors at once, so it differs a little from the tuners (worst case +3.2 % on the forward deceleration); in the tuners the motors float about 25 ms after the speed passes 30 in/s, and they read the Pinpoint's quantised speed.

## Chassis numbers

Only what the design sets; everything else is `MecanumConfig.generic18Inch()` (model: `MecanumWheel`). Mass and wheel diameter come from Hornet's profile in `RobotProfiles` (11.3 kg, 96 mm). The two losses, `MecanumConfig.wheelFrictionNm` and `rollerRollingResistance`, are zero unless the design sets them, so other robots are unchanged.

| Number | Value | How obtained |
|---|--:|---|
| `massKg` | 11.3 | Hornet's `Constants` (`mass`); the only drivetrain figure in the repository. |
| `battery` (`RobotRig`) | 12.6 V | Estimate: a full 12 V battery. The shooter and intake motors run on it too. |
| `wheelFrictionNm` | 0.12 | Fitted to the forward deceleration; 6.5 % of one wheel's stall torque. |
| `wheelDampingNmsPerRad` | 0.0034 | Fitted to the forward top speed, with the friction above and the battery. |
| `rollerViscousNsPerM` | 2.2 | Fitted to the sideways top speed. |
| `rollerRollingResistance` | 0.053 | Fitted to the sideways deceleration; the share of its load a wheel's rollers drag with. |
| `rollerFriction` | 0.5 | Chosen, not fitted: the cap of the roller drag, kept well above it so it takes no grip away. |

Generic values behind them: 96 mm mecanum wheels (radius 0.048 m), goBILDA Yellow Jacket motors behind 13.7:1 (a 435 rpm motor: stall 1.834 N m, free speed 45.55 rad/s, 9.2 A stall at 12 V, from the data sheet), the same for the intake and the belt, whose rollers are the motor's speed over 13.7, wheelbase and track 0.36 m, body 0.457 x 0.457 x 0.305 m, centre of mass 0.08 m up, traction friction 0.8.

- **Wheels and motors.** 76.84 in/s is 1.95 m/s, 40.7 rad/s on 96 mm wheels, 89 % of a 435 rpm motor's free speed at 12 V; 312 rpm motors could not reach it. Hornet's repository names neither wheels nor drive motors, so both are estimates.
- **Wheel friction is constant, not viscous.** A real gearbox loses about the same torque at any speed. A robot that loses 0.835 m/s^2 on average coasting from 30 in/s to a stop and still reaches 89 % of free speed cannot be pure viscous drag (drag that stops it that hard, 0.04 N m s/rad, would top out at 45 in/s, not 77). The two measurements fix the constant part (0.12 N m per wheel) and the viscous part (0.0034).
- **Sideways is slower and stops harder.** A mecanum wheel's rollers turn 1.41 times as fast as the robot moves sideways. Their bearings and tires add viscous drag that lowers the top speed (2.2 N s/m per wheel) and constant drag that stops the coast (0.053 of the wheel's load); viscous drag alone would cost 22 % of the sideways top speed to match the deceleration.
- **The battery voltage was not touched.** It would also fit the top speed, but it belongs to every motor and nothing says it is 12.0 or 12.6 V at the wheels.

## Shooter numbers

Hornet's `Shooter` holds a table from the distance to its aim point (Pedro (8, 136), the goal's corner) to the flywheel speed that scored on the real robot (35 to 160 in, 860 to 1280 ticks/s), adds 12 ticks/s, and drives two flywheels with a PIDF controller (`kP` 0.004, `kF` 0.00049). It has no hood: the launch angle is fixed. Its manual shots are 1000 and 1250 ticks/s.

| Number | Value | How obtained |
|---|--:|---|
| Launch direction (`launchers.shooter`) | 55 deg | Fitted (was 41.3). At 41 degrees nothing from 35 to 60 in clears the goal's front panel. |
| `efficiency` | 0.49 | Fitted (was 0.50). Rim radius 0.05 m times efficiency is 0.0245 m; ballistics from 75 in out gave 0.025. |
| `frictionNmsPerRad`, both flywheels | 8.0e-5 | From Hornet's `kF` (was 1.0e-5): a data fix of the flywheel plant, not of the launcher. |
| Exit point `[-0.12, 0, 0.26]`, radius 0.05 m, flywheel inertia 2.5e-4 | n/a | Estimates; nothing in the repository gives them. |

- **Angle.** A ball leaves 0.44 m above the floor; the goal's front panel is 0.984 m high plus the ball's radius, so the ball must be above 1.05 m there. From the nearest stances the panel is 0.67 m from the exit, and at 41 degrees even infinite speed reaches only 1.03 m. At 55 degrees the near shots clear it, still arrive at the boards from above, and one factor (0.0245 m) serves the whole table. The optimum is flat: 52.5 to 57.5 degrees with efficiency 0.48 to 0.49 score all 99 stances; 50 degrees loses 2 or 3 of the nearest; 60 degrees holds +-3 % of speed in only 82 to 92.
- **Flywheel load.** `kF` is the power that holds a speed: 0.49 at 1000 ticks/s, 0.6125 at 1250. For the bare 6000 rpm motor (stall 0.134 N m, free speed 628 rad/s) that is 0.0178 and 0.0223 N m at 224 and 280 rad/s, a viscous load of 8.0e-5 N m s/rad at both. With 1.0e-5 the wheels settled about 3 % above their setpoint and the far routine's shots left the field; with 8.0e-5 they settle within 0.5 %.

## Shot sweep

`ShotSweepTest` (module `simulator`, no Hornet package) puts the fixed robot, mapped as Hornet, on the DECODE field at 35 to 160 in from the aim point and up to five bearings over every legal stance: robot inside the field, a part over a launch zone, standing in nothing (99 per alliance; none legal beyond 145 in). It loads one ARTIFACT and fires it with the real `Mechanisms.launch` at the table's speed plus 12, flywheels exactly on speed. The red goal mirrors the blue and gives the same numbers. Windows are what every stance of that distance holds, probed in 1 % and 1 degree steps up to +-6 % and +-3 degrees.

| Distance, in | Stances | Score at table speed | Speed window, % | Aim window, deg | Meets the boards at, m |
|--:|--:|--:|--:|--:|--:|
| 35 | 5 | 5/5 | -6 to +6 | -3 to +3 | 1.24 |
| 75 | 5 | 5/5 | -6 to +6 | -3 to +3 | 1.06 to 1.09 |
| 145 | 1 | 1/1 | -4 to +4 | -3 to +3 | 1.00 |

All 99 stances score for both alliances (unfitted robot: 69 of 99, none from 35 to 60 in). +-3 % of speed holds in 99, +-3 degrees of aim in 96, and no shot at the table's speed leaves the field. Above the window a far shot clips the top of the boards (1.365 m) and leaves the field; below it the ball falls short. Three balls fired 0.3 s apart all reach the ramp in 98 of 99 volleys (red) and 97 of 99 (blue); [DECODE](../fields/decode.md) explains the rest. The test fails below 95 % scoring, a 2 point gap between alliances, 90 % holding +-3 % of speed, 85 % holding +-3 degrees, 93 % complete volleys, or a shot that meets the boards outside 0.9 to 1.35 m.

## Hornet's own routines

`HornetAutoRunTest` plays each auto for 30 s through the whole chain, from Hornet's code to the field's score. Runs are not repeatable, so scores vary. **The test passes when an auto classifies at least 12 artifacts (Close) or 7 (Far) within three runs.** Typical scores are 15 (Close) and 9 (Far) on both alliances; the far routine fires four volleys and fills the ramp (nine CLASSIFIED, three OVERFLOW). With the unfitted goal and launcher the far routine scored 9 / 2 (blue / red) and, with the flywheel load at 1.0e-5, 1 / 2 (not re-run since). `HornetTeleopTest` drives the robot across open floor for 2 s and stops it, then holds the left trigger to align and shoot three preloaded artifacts from the corner start; it asserts that fewer than three remain in the robot after 12 s. All three CLASSIFIED within 4 s and making the pattern was observed, not asserted.

## What is still an estimate

- **Wheels, motors, battery voltage.** The four measurements fix only their combined effect. 104 mm wheels or another motor would move the split between the fitted losses, not the measurements. How the losses divide between constant and viscous in the wheels and the rollers is plausible, not measured (four measurements fit five knobs).
- **Everything Pedro's constants do not touch:** acceleration (traction friction 0.8 and motor torque), turning speed (5.1 rad/s at full power), tire stiffness, centre of mass height, body size.
- **Dead band.** A wheel does not turn until the motor's torque passes its friction, so commands below about 0.06 do nothing. Real drivetrains behave alike, but 0.06 is a consequence of the fit, not a measurement.
- **Shooter geometry.** Exit point, flywheel radius and inertia, and the radius-versus-efficiency balance (only the product is fitted). The angle is fitted against a table tuned on a robot whose geometry is not in the repository.
- **No air drag and no wheel spin.** The engine's default linear damping of 0.05/s still acts on flying pieces (about 3 % of the speed over a 0.7 s flight).
- **Table interpolation.** The sweep interpolates linearly; SolversLib's `InterpLUT` may use a monotone cubic. Between entries the difference is a few ticks/s, well inside the windows.
- **Flywheel droop.** The real flywheel droops when a ball passes; the sweep keeps the speed exact, the routines do not.

## Reproducing it

| Result | Command |
|---|---|
| Pedro's tuners | `.\gradlew.bat --console=plain -q :acceptance-tests:test --tests '*HornetBringupTest.pedrosOwnTunersMeasureTheSimulatedDrivetrain' '-Pftcsim.hornetPackage=<imported Hornet package>'` |
| Drivetrain calibration | `.\gradlew.bat --console=plain -q :acceptance-tests:test --tests '*HornetDrivetrainCalibrationTest'` |
| Shot sweep | `.\gradlew.bat --console=plain :simulator:test --tests '*ShotSweepTest' -i` |
| Hornet autos | `.\gradlew.bat --console=plain -q :acceptance-tests:test --tests '*HornetAutoRunTest' '-Pftcsim.hornetPackage=<imported Hornet package>'` |

The sweep prints its tables as `SWEEP>` lines; the autos print `HORNET-AUTO>` lines into `acceptance-tests/build/test-results/test/TEST-dev.ftcsim.acceptance.HornetAutoRunTest.xml`.
