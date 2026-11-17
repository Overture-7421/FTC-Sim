# The BIOBUZZ stub program

What the stand-in program for the BIOBUZZ robot proves and how to run it in the window; read it before trusting a BIOBUZZ robot you have not yet written code for.

There is no BIOBUZZ robot program yet. `BiobuzzStubTeleOp` (`acceptance-tests\src\scenarios\java\dev\ftcsim\scenarios\opmodes`) is a short TeleOp written as a team writes one, knowing nothing of the simulator: mecanum drive on the sticks, a goBILDA Pinpoint called `odopods`, and a Limelight called `limelight`. It shows what it learns on telemetry. `BiobuzzStubSessionTest` (`acceptance-tests`) runs it on the BIOBUZZ field and checks:

| Check | How |
|---|---|
| The robot drives as the sticks say | Forward moves ahead along the heading, left strafes to the robot's left, right stick turns clockwise (true chassis pose, red wall start) |
| The odopods work | The Pinpoint's X, Y and heading equal the true motion in the robot's start frame (within 15 mm and 3 degrees), and its pod X count follows the travel at the 4-bar pod resolution the stub sets |
| The Limelight sees the HIVE tags | Standing 1 m from tag 31 of the red HIVE, tags 30 to 33 are listed, tag 31 among them, `botpose` has no tags, and turning the robot 12 degrees to the left moves the target to the right in the image (`tx` rises by more than 6 degrees) |

**What it cannot prove.** The Pinpoint is ideal ([pinpoint.md](../devices/pinpoint.md)): pod offsets and encoder directions are accepted and ignored, so a wrong offset in the real code does not show here. The offsets in the stub are Hornet's, placeholders until they are measured on the robot. The camera has no pose on this field: every BIOBUZZ tag is on a HIVE, which moves, so `botpose` is empty ([limelight.md](../devices/limelight.md)).

## Running it in the window

1. Make a robot project for your SDK: clone FIRST's `FtcRobotController` at the release you use and open it as the project.
2. Copy `BiobuzzStubTeleOp.java` into `TeamCode`, and change its package line.
3. Put an `ftcsim.json` at the project root, or map the four wheels on the **Robot** screen ([fixed-robots.md](fixed-robots.md)). The names are the stub's:

```json
{ "schema": 2, "name": "BIOBUZZ stub",
  "motors": { "frontLeft":  { "slot": "FRONT_LEFT",  "model": "", "mirrored": true },
              "backLeft":   { "slot": "REAR_LEFT",   "model": "", "mirrored": true },
              "frontRight": { "slot": "FRONT_RIGHT", "model": "", "mirrored": false },
              "backRight":  { "slot": "REAR_RIGHT",  "model": "", "mirrored": false } } }
```

4. **Open project**, pick BIOBUZZ for the game, choose the `BIOBUZZ Stub` OpMode, Init, wait for the Pinpoint to say `READY`, Start. Drive toward a HIVE and read the telemetry.
