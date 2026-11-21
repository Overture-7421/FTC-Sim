# The BIOBUZZ robot's CAD

How the window's picture of the BIOBUZZ robot is made from the team's Onshape CAD, what moves in it, what was taken from the CAD for the simulation, and what was not. The CAD is the document "23619-26-A-0000 | Main |" (assemblies Chassis, Indexer, Intake, Shooter).

## From the CAD to the window

| Step | What | Where |
|---|---|---|
| Export | The configured Main assembly as glTF from Onshape (MasterSketch=false, coarse tessellation, 1,187 mesh instances). It is not in the repository | the team's Onshape |
| Convert | `tools\cad\convert_biobuzz.py <robot.gltf> <biobuzz.model> [triangle budget]` leaves out fasteners and parts under a size limit, and **rebuilds the robot from simple shapes**: a cylinder, ring or box for each round or blocky part (gears, sprockets, wheels, motors, standoffs), a flat sheet with the outline and the larger holes of the CAD for each plate (extruded, so the purple lattice plates stay lattice), a lightly reduced mesh for belts and chains, and a heavily reduced one for anything else. The four drive wheels are not the CAD's: they are drawn from scratch as 112 mm by 35 mm mecanum wheels (two side discs, an axle and ten angled rollers). Lexan parts (the colibri plates and the number holders) get a translucent material. The Limelight 3A camera and mount use reduced meshes from the shooter assembly, including the camera?s separate face materials. The Stealth flywheels have 32-sided rims aligned to their axle. The converter removes internal bearings and makes the small shafts hexagonal. It splits the robot into rigid groups and writes each as 16-bit quantised positions with a colour and an opacity per chunk | `tools\cad` |
| Ship | `biobuzz.model` (gzip, 171 KB, 29,740 triangles; the budget is 30 thousand) is a resource of `desktop-ui` | `dev\ftcsim\ui\model\biobuzz.model` |
| Read | `RobotModel` reads it into groups; `ModelPoser` finds where each group is from the snapshot | `dev.ftcsim.ui.model` |
| Draw | `ModelRenderer` uploads every chunk to the GPU once and draws a group with one call per chunk (no per-frame upload), flat shaded from screen derivatives, with the same light and shadow as the rest of the scene. Opaque chunks first, with face culling off (plates are single sheets); translucent chunks after, blended, without depth writes and casting no shadow | `dev.ftcsim.ui.render` |

The offscreen render check writes front, side, top, shooter close-up and articulated views under `desktop-ui/build/screens` and reports frame time in `RobotModelRenderTest`. To make the model lighter or heavier, change the budget or the tolerances at the top of the converter (`PLATE_TOLERANCE`, `MIN_HOLE_AREA`, `MIN_SIZE`, `FASTENER`).

A build without the model resource draws the robot as boxes, as before.

## Frames

The CAD faces -Y with +X to its left and Z up. The simulator's frame is X forward, Y left, Z up, so `x = -(y_cad - 0.0385)`, `y = x_cad`, `z = z_cad - 0.1264`: the origin is the centre of the four wheels (CAD Y 0.0385) at the height of the chassis body's centre (0.1824 m above the floor, 0.1264 above the wheel axles). The simulated chassis is still the generic 18 inch box of every robot ([hornet-fit](hornet-fit.md)); only the picture is the CAD's, so the CAD's 0.44 by 0.59 m footprint and the physics box differ.

## What moves

The simulator publishes the value of each joint as a body of the snapshot (`RobotJoint`; `BodyKinds.JOINT_BASE` plus its ordinal, the value in radians as X), and the turret as the body `BodyKinds.TURRET`. No wire message changed for this. Each model group turns about a pivot taken from the CAD's mates or part positions. The converter removes the exported turret yaw using the camera mount's lateral axis, reads the hood rest angle from its side plate and groups the two feeder shafts by their centres.

| Group (joint) | Source of the value | Ratio and range |
|---|---|---|
| Four wheels | the chassis's wheel angles | direct; positive rolls the robot forward |
| Turret, with everything on it | the turret's yaw (servo or motor) | the turret turns about its axis, x 0.045 m ahead of centre in the team code (the CAD places it at x 0.089 m) |
| Hood (carries the flywheel and feed rollers) | the hood servo | joint = 1 degree + servo angle / 5.0694 (365:72 gears, from the team's actuator table). The CAD allows 1 to 25 degrees, but the simulator's stop is widened to 45 degrees so that a HIVE's CELL can be reached from near and far (`HOOD_RANGE_RADIANS`): the servo is a Super Speed (300 degrees), 223 degrees of it (position 0.7435) take the hood to its stop and the stop holds it there for any position above. The drawn hood swings further than the CAD's |
| Flywheel and feed rollers | the first shooter motor | motor / 1.433 (43:30) |
| Intake roller | the first intake motor | motor / 2.5 |
| Indexer rollers and sprockets (one group per shaft) | the first conveyor motor | motor / 2.5 |
| Colibri arms and printed plate | the left and right colibri servos | arm = servo angle / 1.333 (40:30), the CAD pose is 60 degrees above the simulated down position. A command of 0.0667 lifts the plate about 90 degrees |
| Stopper (the goBILDA floodgate switch) | the stopper servo | 1:1 |
| Encoder gears (32T and 28T) | the turret's yaw | 5.5 and 44/7 turns per turret turn, against the turret |

The 3D-printed plate that joins the arms is missing from the Onshape assembly. The converter adds it between the CAD arm noses. Its simulated width is 424 mm, with an estimated 30 mm depth and 8 mm thickness. The matching kinematic box contacts loose pieces across the full width of the plate.

The signs of the rollers are the direction that moves pieces the way the robot takes them in; the CAD cannot say which way a real motor turns, so the picture follows the motor's mapped direction.

## What the simulation takes from the CAD

- **Odometry pods.** The forward-measuring pod's wheel rolls along the CAD's Y at x -0.0704, y 0.1997, so it sits 70.4 mm to the right of the centre of the wheels; the strafe pod's wheel rolls along X at x -0.1136, y -0.0204, 58.9 mm ahead of it. The Pinpoint takes `setOffsets(-2.772, 2.319, INCH)`, which the stub uses. The Pinpoint model is ideal and ignores offsets, so they only matter on the real robot.
- **Turret axis.** (0, -0.0505) in the CAD, which is x 0.089 ahead of the wheels' centre.
- **Gear ratios.** The 1.0185:1 turret, the encoders' 5.5 and 44/7, and the ratios in the table above, all as the CAD and the team's table say.
- **Hood limits.** 1 to 25 degrees, from the revolute mate; the simulator allows 1 to 45 degrees (see the table above), an estimate that is not the CAD's.

## The path of the pieces

The pieces the robot carries are drawn where the simulation has them, so their path (`RobotDesign.BIOBUZZ_PATH`, the centre of a POLLEN, robot frame in metres) follows the indexer in the CAD instead of cutting through it:

| Point | Where | What is there in the CAD |
|---|---|---|
| 0 (0.27, 0, -0.14) | in front of the robot, on the floor | where the intake takes a piece in |
| 1 (0.15, 0, -0.10) | behind the intake roller, between the front wheels | the curve of compliant wheels that carry the piece up |
| 2, 3 (0.115, 0, -0.05), (0.098, 0, 0.0) | up and back along that curve | |
| 4 (0.089, 0, 0.06) | the foot of the column | between the cross shafts at x 0.042 and 0.137 (gap 85 mm, a POLLEN is 71 mm), on the CAD column centreline |
| 5 (0.089, 0, 0.215) | the top of the column, inside the turret | the feed rollers behind, the flywheels ahead |
| 6 (0.092, 0, 0.233) | where a piece waits for the shot | pinched between the feed rollers (x 0.045, z 0.205 to 0.245) and the flywheels' rim (axle x 0.162, z 0.2146, radius 0.0381); a ball of radius 0.0356 touching both |

The **stopper** (`StorageSpec.Gate`, the stopper servo) holds the pieces at point 4, the foot of the column: while its servo is under 60 degrees, a piece that has not passed that point waits there however hard the rollers drive, and a piece loaded at the start of a session or after a reset starts there too. That is how the balls are preloaded: the intake brings them up against the shut stopper, and an open one lets them go on to the flywheels. (The CAD's stopper, the goBILDA floodgate switch, is drawn about 0.1 m behind the column; the hold is at the column because that is where the path is.) The intake roller drives the first 0.20 m, the indexer the rest. From point 5 the path is inside the turret: a piece there turns with it about the turret's axis (`StorageSpec.Turned`), so what the window shows is the piece riding the turret's yaw. The shot leaves from the **exit** (`LaunchSpec.Hood.exit`), where the ball, riding round the top of the flywheels' rim, is let go by the end of the hood. The two 3 in wheels sit side by side at the centre with their axle at (0.162, 0, 0.2146), and the hood is a pair of side plates that carry the wheels and pivot about that axle, so the exit swings with the hood: the ball's centre is at the axle plus 0.0737 m (the wheel's radius and the ball's) in the direction 90 degrees plus the launch elevation, that is about (0.110, 0.267) at 45 degrees and back over the wheels at (0.09, 0.215) at 89 degrees, turned with the turret and heading where the turret points. The CAD camera sits ahead of the wheels. A robot with no hood uses the exit at the fixed 55 degrees. The flywheels are 3 in wheels behind the 43:30 belt, so a ball leaves at 0.49 of the rim speed, the motor's speed times 0.0381 m / 1.4333 (the 6000 rpm motor under load reaches about 2130 encoder counts a second, a ball speed of about 6 m/s). The launch direction is the same as before (45 degrees plus the hood, turned by the turret). The coordinates are read off the CAD's rollers, shafts, flywheels and hood plates; the exit and where the piece waits are worked out from that geometry rather than measured, so a piece still passes through some parts of the picture (a roller squeezes it, as a real one does). DECODE's robot keeps its old path.

## Simulation estimates

- **Limelight calibration.** The visualization uses the CAD camera and mount. The simulated sensor uses the estimated optical pose in `RobotDesign.BIOBUZZ_CAMERA`.
- **The colour sensors.** The profile's `upperSensor` (0.089, 0.065, 0.060) and `lowerSensor` (0.187, 0.065, -0.112), on the left wall looking right, are placed where the first and fourth ball of the queue wait (the stopper is path point 4; the others queue 0.0711 m apart along the path), so `upperSensor` reads a ball only when one has reached the stopper and `lowerSensor` only when four are queued. Estimates: to be replaced by measured mounts ([fixed-robots](fixed-robots.md)).
- **The colibri arms' travel and the stopper's open position.** The mates have no limits. The simulation opens the stopper at 60 degrees of servo (position 0.0333 of the 5-turn servo) and the arms are drawn for the whole servo range.
- **The pivot of the stopper.** Approximated by the switch's own centre.
- **The magazine holds four POLLEN** (the robot's four preloads); queued behind the stopper they stand 0.0712 m apart along the path, the last one near the intake's mouth. The intake roller's inertia is estimated at 4e-5 kg m^2 (a light roller on the compliant wheels), so a braked intake motor stops in about 0.1 s; DECODE's estimate stays 2e-4.
- **The hood's launch geometry.** The hood sets the launch elevation as 44 degrees plus its angle (45 degrees at the bottom of its range, 54.9 at the top): an estimate, the team's real shot table replaces it.

## Making the model again

1. Export the Main assembly as glTF with MasterSketch=false and coarse tessellation from [Onshape](https://cad.onshape.com/documents/e7e7665d44ea06dec7272c2c/w/c0a9d4ca8aed0e86f2df057e/e/da2feab4c9d65ffa8a7d6eef?configuration=MasterSketch%3Dfalse) and `pip install DracoPy numpy fast-simplification shapely mapbox-earcut`.
2. `python tools\cad\convert_biobuzz.py "<robot>.gltf" desktop-ui\src\main\resources\dev\ftcsim\ui\model\biobuzz.model 30000`. It prints the triangles per kind and per group.
3. Run `:desktop-ui:test --tests "*RobotModelRenderTest*"` and look at `desktop-ui\build\screens\biobuzz-cad-*.png`.

A part that moves differently in a new CAD changes the rules at the top of the script (`GROUPS` and `assign`), which hold every pivot and axis with the mate it came from.
