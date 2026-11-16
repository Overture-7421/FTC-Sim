# Hornet's simulated drivetrain

The fixed DECODE robot (`RobotDesign` in `simulator`, `robots/fixed-robots.md`) holds Hornet's fitted numbers as constants,
and `RobotDesignTest` spells every one of them out, so a change to a fitted number fails a test and is made on purpose. Its
drivetrain is calibrated against the four constants that Hornet's team measured on the real robot with Pedro Pathing's own
tuners (`pedroPathing/Constants.java`), because Pedro predicts its braking distance from them and a robot that coasts or tops
out differently makes autos overshoot.

## Result

Pedro's own tuners, chosen from Hornet's Tuning menu and run on the simulated robot
(`HornetBringupTest#pedrosOwnTunersMeasureTheSimulatedDrivetrain`, which needs the imported Hornet package):

| Constant                                 | Real robot | Simulated | Difference |
|------------------------------------------|-----------:|----------:|-----------:|
| `xVelocity`, forward top speed, in/s     |      76.84 |     76.84 |      0.0 % |
| `yVelocity`, sideways top speed, in/s    |      58.91 |     58.69 |     -0.4 % |
| `forwardZeroPowerAcceleration`, in/s^2   |     -32.88 |    -32.75 |     -0.4 % |
| `lateralZeroPowerAcceleration`, in/s^2   |     -72.06 |    -73.03 |     +1.3 % |

The unfitted generic robot reached 86.33 and 84.29 in/s and slowed by only about 0.5 in/s^2 forward (it rolled on for 66 s
and 13 m) and 1.54 in/s^2 sideways. `HornetDrivetrainCalibrationTest` (acceptance tests, no Hornet package needed) repeats the
four measurements straight on the chassis and keeps all four within 5 %. It reads the exact speed and floats the motors at
once, so it lands a little differently from the tuners (worst case +3.2 % on the forward deceleration); in the tuners the
motors float about 25 ms after the speed has passed 30 in/s, and they read the Pinpoint's quantised speed.

## The chassis numbers

Only what the design sets is listed; everything else is `MecanumConfig.generic18Inch()`. The design reads mass and wheel
diameter from the student's mapping; Hornet's mapping gives 11.3 kg and 96 mm.

| Number                        | Value   | Where it comes from                                                                         |
|-------------------------------|--------:|---------------------------------------------------------------------------------------------|
| `massKg`                      |    11.3 | Hornet's `Constants` (`mass`). The only drivetrain figure the repository states.            |
| `battery` (`RobotRig`)        |    12.6 | Estimate: a full 12 V battery. The shooter and intake motors run on it as well.             |
| `wheelFrictionNm`             |    0.12 | Fitted to the forward deceleration. 6.5 % of the stall torque of one wheel.                 |
| `wheelDampingNmsPerRad`       |  0.0034 | Fitted to the forward top speed, together with the friction above and the battery.          |
| `rollerViscousNsPerM`         |     2.2 | Fitted to the sideways top speed.                                                           |
| `rollerRollingResistance`     |   0.053 | Fitted to the sideways deceleration; the share of its load a wheel's rollers drag with.     |
| `rollerFriction`              |     0.5 | Chosen, not fitted: the cap of the roller drag, kept well above it so it takes no grip away. |

Behind these, from the generic robot: 96 mm mecanum wheels (radius 0.048 m), goBILDA 5203 435 rpm motors (stall 1.834 N m,
free speed 45.55 rad/s, 9.2 A stall at 12 V, from the goBILDA data sheet), wheelbase and track 0.36 m, a 0.457 x 0.457 x
0.305 m body, centre of mass 0.08 m above the floor, traction friction 0.8. The model is `MecanumWheel`; the two losses are
`MecanumConfig.wheelFrictionNm` and `rollerRollingResistance`, both zero unless the design sets them, so every other robot is
unchanged.

- **Wheels and motors.** 76.84 in/s is 1.95 m/s, 40.7 rad/s on 96 mm wheels, 89 % of a 435 rpm motor's free speed at 12 V;
  312 rpm motors could not reach it. The drive motors are not named anywhere in Hornet's repository, so both are estimates.
- **Friction in the wheels is constant, not viscous.** A real gearbox loses about the same torque at any speed. A robot that
  loses 0.835 m/s^2 on average coasting from 30 in/s to a stop and still reaches 89 % of the free speed cannot be made of
  viscous drag alone (drag that stops it that hard, 0.04 N m s/rad, would top it out at 45 in/s, not 77). The two
  measurements fix the constant part (0.12 N m per wheel) and the viscous part (0.0034).
- **Sideways is slower and stops much harder.** A mecanum wheel's rollers turn 1.41 times as fast as the robot moves sideways.
  Their bearings and tires add a viscous drag that lowers the top speed (2.2 N s/m per wheel) and a constant one that stops
  the coast (0.053 of the wheel's load); viscous drag alone would cost 22 % of the sideways top speed to match the
  deceleration.
- **The battery voltage was not touched.** It would also have fitted the top speed, but it belongs to every motor on the
  robot and nothing says it is 12.0 or 12.6 V at the wheels.

## The shooter

Hornet's `Shooter` holds a table from the distance to its aim point (Pedro (8, 136), the goal's corner) to the flywheel speed
that scored on the real robot (35 to 160 in, 860 to 1280 ticks/s), adds 12 ticks/s, and drives the two flywheels with a PIDF
controller (`kP` 0.004, `kF` 0.00049). It has no hood: the launch angle is fixed. Its manual shots are 1000 and 1250 ticks/s.

| Number                                   | Value     | Basis                                                                                   |
|------------------------------------------|----------:|-----------------------------------------------------------------------------------------|
| launch direction (`launchers.shooter`)   | 55 deg    | Fitted (was 41.3). At 41 degrees nothing from 35 to 60 in clears the goal's front panel. |
| `efficiency`                             |      0.49 | Fitted (was 0.50). With the 0.05 m radius, rim radius times efficiency is 0.0245 m; ballistics from 75 in out gave 0.025. |
| `frictionNmsPerRad` of both flywheels    |   8.0e-5  | From Hornet's own `kF` (was 1.0e-5). A data fix of the flywheel plant, not of the launcher. |
| exit point `[-0.12, 0, 0.26]`, radius 0.05 m, flywheel inertia 2.5e-4 | | Estimates; nothing in the repository gives them. |

**Angle.** A ball leaves 0.44 m above the floor, and the goal's front panel is 0.984 m high with the ball's radius on top, so
the ball must be above 1.05 m when it passes the panel. From the nearest stances the panel is 0.67 m from the exit, and at 41
degrees even an infinite speed reaches only 1.03 m there; at 55 degrees the near shots clear it, still arrive at the boards
from above, and one factor 0.0245 m serves the whole table. The optimum is flat: 52.5 to 57.5 degrees with efficiency 0.48 to
0.49 score all 99 stances; 50 degrees loses 2 or 3 of the nearest; 60 degrees holds +-3 % of speed in only 82 to 92.

**Flywheel load.** `kF` is the power that holds a speed: 0.49 at 1000 ticks/s and 0.6125 at 1250. For the bare 6000 rpm motor
(stall 0.134 N m, free speed 628 rad/s) that is 0.0178 and 0.0223 N m at 224 and 280 rad/s, a viscous load of 8.0e-5 N m s/rad
at both speeds. With 1.0e-5 the wheels settled about 3 % above their setpoint and the far routine's shots flew out of the
field; with 8.0e-5 they settle within 0.5 % of it.

### Shot sweep

`ShotSweepTest` (module `simulator`) puts the fixed DECODE robot mapped as Hornet on the DECODE field at distances from 35 to
160 in from the aim point and up to five bearings over every stance where the robot fits in the field, has a part over a
launch zone and does not stand in anything (99 per alliance; nothing is legal beyond 145 in). It loads one ARTIFACT and fires
it with the real `Mechanisms.launch` at the table's speed plus 12, the flywheels exactly on that speed. The red goal is the
blue goal's mirror image and gives the same numbers shot for shot. Representative rows (windows are the ones every stance of
that distance holds, probed in steps of 1 % and 1 degree up to +-6 % and +-3 degrees):

| Distance, in | Stances | Score at the table speed | Speed window, % | Aim window, deg | Meets the boards at, m |
|-------------:|--------:|-------------------------:|----------------:|----------------:|-----------------------:|
|           35 |       5 |                      5/5 |        -6 to +6 |        -3 to +3 |                   1.24 |
|           75 |       5 |                      5/5 |        -6 to +6 |        -3 to +3 |             1.06 to 1.09 |
|          145 |       1 |                      1/1 |        -4 to +4 |        -3 to +3 |                   1.00 |

All 99 stances score for both alliances (the unfitted robot: 69 of 99, none from 35 to 60 in); +-3 % of speed holds in 99,
+-3 degrees of aim in 96; no shot at the table's speed leaves the field. Above the window a far shot clips the top of the
boards (1.365 m) and leaves the field; below it the ball falls short. Three balls fired 0.3 s apart all reach the ramp in 98
of 99 volleys (red) and 97 of 99 (blue); `docs/fields/decode.md` explains the rest. The test fails below 95 % scoring, a 2
point difference between the alliances, 90 % holding +-3 % of speed and 85 % holding +-3 degrees, and a shot that does not
meet the boards between 0.9 and 1.35 m.

### Hornet's own routines

`HornetAutoRunTest`, the whole chain from Hornet's code to the field's score, 30 s each: Close Auto scores 15 / 15 (blue /
red) and Far Auto 9 / 9. The far routine fires four volleys and fills the ramp: nine CLASSIFIED and three OVERFLOW. With the
unfitted goal and launcher the far routine scored 9 / 2 and, with the flywheel load at 1.0e-5, 1 / 2.
`HornetTeleopTest` drives the robot 1.27 m across open floor in 2 s and stops it, and holds the left trigger to align and shoot
three preloaded artifacts from the corner start: all three are CLASSIFIED within 4 s and make the pattern.

## What is still an estimate

- The wheels, the motors and the battery voltage: the four measurements fix only their combined effect. Wheels of 104 mm or
  another motor would move the split between the fitted losses, not the measurements. How the losses divide between constant
  and viscous in the wheels and in the rollers is plausible rather than measured (four measurements fit five knobs).
- Everything that Pedro's four constants do not touch: acceleration (set by the traction friction of 0.8 and the motors'
  torque), turning speed (5.1 rad/s at full power), tire stiffness, the centre of mass height, and the body's size.
- A wheel does not turn until the motor's torque passes its friction, so commands below about 0.06 do nothing. Real
  drivetrains behave alike, but 0.06 is a consequence of the fit, not a measurement.
- The shooter's exit point, flywheel radius and inertia, and the balance between radius and efficiency (only their product is
  fitted). The launch angle is fitted against a table that was itself tuned on a robot whose geometry is not in the repository.
- No air drag and no spin from the wheels: a ball leaves without spin. The engine's default linear damping of 0.05/s still
  acts on flying pieces (about 3 % of the speed over a flight of 0.7 s).
- The table's own interpolation: the sweep interpolates linearly, SolversLib's `InterpLUT` may use a monotone cubic; between
  the table's entries the difference is a few ticks/s, well inside the windows.
- The real flywheel droops when a ball passes; the sweep keeps the speed exact, the routines do not.

## Reproducing it

```
.\gradlew.bat --console=plain -q :acceptance-tests:test --tests '*HornetBringupTest.pedrosOwnTunersMeasureTheSimulatedDrivetrain' '-Pftcsim.hornetPackage=<imported Hornet package>'
.\gradlew.bat --console=plain -q :acceptance-tests:test --tests '*HornetDrivetrainCalibrationTest'
.\gradlew.bat --console=plain :simulator:test --tests '*ShotSweepTest' -i
.\gradlew.bat --console=plain -q :acceptance-tests:test --tests '*HornetAutoRunTest' '-Pftcsim.hornetPackage=<imported Hornet package>'
```

The shot sweep prints its tables as `SWEEP>` lines. The auto routines print theirs as `HORNET-AUTO>` lines into
`acceptance-tests/build/test-results/test/TEST-dev.ftcsim.acceptance.HornetAutoRunTest.xml`.
