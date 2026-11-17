# The BIOBUZZ robot's CAD

How the window's picture of the BIOBUZZ robot is made from the team's Onshape CAD, what moves in it, what was taken from the CAD for the simulation, and what was not. The CAD is the document "23619-26-A-0000 | Main |" (assemblies Chassis, Indexer, Intake, Shooter).

## From the CAD to the window

| Step | What | Where |
|---|---|---|
| Export | The whole robot as glTF from Onshape (Draco compressed, 24 million unique triangles, 47 million with instances). It is not in the repository | the team's Onshape |
| Convert | `tools\cad\convert_biobuzz.py <robot.gltf> <biobuzz.model> [triangle budget]` leaves out fasteners and parts under 16 mm, merges the thousands of tiny pieces some parts are made of, simplifies curved parts to a size-based triangle count, **keeps thin plates exactly** (any reduction, even a lossless one, tears them), splits the robot into rigid groups and writes each as 16-bit quantised positions with a colour per vertex | `tools\cad` |
| Ship | `biobuzz.model` (gzip, about 9.5 MB, 1.6 million triangles) is a resource of `desktop-ui` | `dev\ftcsim\ui\model\biobuzz.model` |
| Read | `RobotModel` reads it into groups; `ModelPoser` finds where each group is from the snapshot | `dev.ftcsim.ui.model` |
| Draw | `ModelRenderer` uploads every chunk to the GPU once and draws a group with one call per chunk (about 40 calls, no per-frame upload), flat shaded from screen derivatives, with the same light and shadow as the rest of the scene | `dev.ftcsim.ui.render` |

Measured on an RTX 5080: 0.7 ms a frame for the robot with its shadow (`RobotModelRenderTest`, which also writes `build\screens\biobuzz-cad-*.png`). Not measured on an integrated GPU. To make the model lighter, run the converter with a smaller budget; curved parts follow it, plates do not.

A build without the model resource draws the robot as boxes, as before.

## Frames

The CAD faces -Y with +X to its left and Z up. The simulator's frame is X forward, Y left, Z up, so `x = -(y_cad - 0.0385)`, `y = x_cad`, `z = z_cad - 0.1264`: the origin is the centre of the four wheels (CAD Y 0.0385) at the height of the chassis body's centre (0.1824 m above the floor, 0.1264 above the wheel axles). The simulated chassis is still the generic 18 inch box of every robot ([hornet-fit](hornet-fit.md)); only the picture is the CAD's, so the CAD's 0.44 by 0.59 m footprint and the physics box differ.

## What moves

The simulator publishes the value of each joint as a body of the snapshot (`RobotJoint`; `BodyKinds.JOINT_BASE` plus its ordinal, the value in radians as X), and the turret as the body `BodyKinds.TURRET`. No wire message changed for this. Each model group turns about a pivot taken from the CAD's mates or part positions.

| Group (joint) | Source of the value | Ratio and range |
|---|---|---|
| Four wheels | the chassis's wheel angles | direct; positive rolls the robot forward |
| Turret, with everything on it | the turret's yaw (servo or motor) | the turret turns about its axis, `turret.axis` of the mapping (the CAD's is x 0.089, y 0) |
| Hood (carries the flywheel and feed rollers) | the hood servo | joint = 1 degree + servo angle / 30.42 (730:24 gears, from the team's actuator table); the CAD allows 1 to 25 degrees, the servo's 300 degrees reaches 10.9 |
| Flywheel and feed rollers | the first shooter motor | motor / 1.433 (43:30) |
| Intake roller | the first intake motor | motor / 2 |
| Indexer rollers and sprockets (one group per shaft) | the first conveyor motor | motor / 2 |
| Colibri arms | the left and right colibri servos | arm = servo angle / 1.333 (40:30), 225 degrees for the whole 300 degrees of servo; the CAD's pose is position 0 |
| Stopper (the goBILDA floodgate switch) | the stopper servo | 1:1 |
| Encoder gears (32T and 28T) | the turret's yaw | 5.5 and 44/7 turns per turret turn, against the turret |

The signs of the rollers are the direction that moves pieces the way the robot takes them in; the CAD cannot say which way a real motor turns, so the picture follows the motor's mapped direction.

## What the simulation takes from the CAD

- **Odometry pods.** The forward-measuring pod's wheel rolls along the CAD's Y at x -0.0704, y 0.1997, so it sits 70.4 mm to the right of the centre of the wheels; the strafe pod's wheel rolls along X at x -0.1136, y -0.0204, 58.9 mm ahead of it. The Pinpoint takes `setOffsets(-2.772, 2.319, INCH)`, which the stub uses. The Pinpoint model is ideal and ignores offsets, so they only matter on the real robot.
- **Turret axis.** (0, -0.0505) in the CAD, which is x 0.089 ahead of the wheels' centre.
- **Gear ratios.** The 2.75:1 turret, the encoders' 5.5 and 44/7, and the ratios in the table above, all as the CAD and the team's table say.
- **Hood limits.** 1 to 25 degrees, from the revolute mate.

## What the CAD does not have

- **The Limelight.** It is not modelled (the BOM and the parts have no camera). Its mount is the default of `camera` in the mapping, 0.16 m ahead, 0.25 m up, pitched 20 degrees, given with the turret facing forward: an estimate to be measured on the robot.
- **The colibri arms' travel and the stopper's open position.** The mates have no limits. The simulation opens the stopper at 60 degrees of servo (position 0.2) and the arms are drawn for the whole servo range.
- **The pivot of the stopper.** Approximated by the switch's own centre.
- **The hood's launch geometry.** The hood sets the launch elevation as 44 degrees plus its angle (45 degrees at the bottom of its range, 54.9 at the top): an estimate, the team's real shot table replaces it.

## Making the model again

1. Export the Main assembly as glTF from Onshape and `pip install DracoPy numpy fast-simplification`.
2. `python tools\cad\convert_biobuzz.py "<robot>.gltf" desktop-ui\src\main\resources\dev\ftcsim\ui\model\biobuzz.model 200000`. It takes a few minutes and a few GB of memory.
3. Run `:desktop-ui:test --tests "*RobotModelRenderTest*"` and look at `desktop-ui\build\screens\biobuzz-cad-*.png`.

A part that moves differently in a new CAD changes the rules at the top of the script (`GROUPS` and `assign`), which hold every pivot and axis with the mate it came from.
