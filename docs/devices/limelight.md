# Limelight 3A

Robot code runs the SDK's own `Limelight3A`, `LLResult`, `LLStatus` ... unchanged (SDK 11.1.0 and 12.0.0 ship the same
Limelight sources). The real driver polls `http://<ip>:5807/...` from its own thread; the simulator opens no sockets and
creates no threads. `SimLimelight3A` (sdk-common) replaces the driver's state machine and runs the SDK's own JSON parsing on
the text that `LimelightModel` (sim-hardware, `dev.ftcsim.hardware.camera`) returns for `GET /results` and `/status`, so field
semantics and SDK differences are the real ones. Between them is `CameraWire` (sim-contracts), an HTTP-level port with
simulated time. A camera type is added with one line in `CameraModels.standard()`. The model's Javadoc holds the pipeline
settings and the estimated numbers.

## From the world to a pose

- **Frames.** The sensor takes a frame every 20 ms (640x480) or 50 ms (1280x960). After the capture latency `cl` the pipeline
  works on it with the chassis and the tags' host bodies as they were **at the capture** (96 physics steps kept,
  interpolated) and finishes after the targeting latency `tl`. A request returns the newest result finished by then; before
  the first, a valid empty document (`v` 0). `ts` is on the simulated clock. Results come from physics steps only, never
  from the poll, so slicing simulated time changes nothing.
- **Visibility.** A tag is found if all four corners are in front of the lens and inside the image, it faces the camera
  (within 80 degrees of its normal), its shortest image side is at least 24 px, and rays from the lens to its middle and
  corners meet nothing nearer (`RayView`; the tag's own host body and a surface within 3 cm of it do not count). Tags on
  tipping bodies (BIOBUZZ hives) follow the body.
- **Estimation.** Corners go through a pinhole with the published field of view, plus deterministic noise (sigma 0.2 px) and
  rounding to 0.25 px. The pose is *estimated* from the pixels and the camera's map, not copied: MegaTag 1 (`botpose`, with
  `botpose_tagcount/_span/_avgdist/_avgarea`, `stdev_mt1`), and with the yaw sent by `updateRobotOrientation` MegaTag 2
  (`botpose_orb`, zero until a yaw arrived; only the position is fitted). A lone tag seen head-on from far can flip to its
  mirror pose, as on the real camera. `botpose` is the pose of the chassis origin in the FTC field frame (metres, degrees, 0
  yaw = facing +X).
- **What the camera believes.** Each pipeline's `fieldMap` is its belief, not the running field's: `decode` or `biobuzz`
  (that game's official fixed tags), `field` (the running field's fixed tags) or `none`. A tag it sees but its map lacks is in
  `Fiducial[]` (`v` 1) and adds nothing to `botpose`, which stays zero with count 0.
- **The driver.** `start/stop/pause`, `setPollRateHz` (1..250, ignored while running), `getTimeSinceLastUpdate` and
  `isConnected` (250 ms) follow the SDK's `scheduleAtFixedRate` grid in virtual time; a request advances the robot's clock
  by its latency (an unanswered one by the SDK's 100 ms connect timeout).

## Numbers and where they come from

| Value | Source |
|---|---|
| Sensor OV5647, FOV 54.5 x 42 degrees, 640x480 at 50 fps and 1280x960 at 20 fps | Limelight 3A quick-start, docs.limelightvision.io/docs/docs-limelight/getting-started/limelight-3a |
| JSON keys and layout; botpose in the FTC field frame; MegaTag 2 yaw input | docs.limelightvision.io/docs/docs-limelight/apis/json-results-specification, `.../apis/ftc-programming`, `.../pipeline-apriltag/apriltag-robot-localization-megatag2` |
| `cl` 37.6 ms and `tl` 18.8 ms at 1280x960 | the specification's examples (37.62; 19.78 for one tag); the 1 ms per tag is **estimated**. Their pixel values reach 822, so those frames were wider than 640 px |
| 640x480: `cl` 12 ms, `tl` 6 ms + 1 ms per tag, jitter +-0.3 ms; pixel noise 0.2 px, quantum 0.25 px, minimum side 24 px, maximum view angle 80 degrees, 2 px edge margin; GET 3 ms, POST 8 ms, pipeline switch after 20 ms, first frame 7 ms after start, camera up 10 min at t=0 | **estimates** |
| `ty` positive up; per-tag `ta` a fraction (0-1), top-level `ta` a percentage | the specification's examples and tables (its table says `ty` down, but a target below the middle has `ty` < 0) |
| intrinsics: centred principal point, focal length from the FOV, no distortion | derived |
| per-tag `t6c_ts`, `t6t_cs`, `t6r_ts`, `t6t_rs`: x right, y down, z into the tag | **assumed** (the AprilTag convention); only the depth in `t6t_cs` and `t6r_fs` (= `botpose` for one tag) are checked |

## Where it is and how it is found

Robot code asks for it by `Limelight3A`, under any name (`hardwareMap.get(Limelight3A.class, "limelight")`); the robot has one
Limelight and the first name that asks is its name. A second name gets an unplugged camera (not connected, never valid) and
the session lists it among its problems. `RobotRig` plugs the camera in (a `CameraRack`) with the field's tags and the bodies
some are stuck on, so it sees the field the session runs on; `CameraRack.problems()` joins `SimulationSession.problems()`.

Its **mount is the one setting a team has**, `RobotMapping.camera` (the Robot page's Limelight window): X forward, Y left,
Z up in metres from the centre of the chassis's box (about 0.18 m above the floor for the fixed chassis), and yaw (positive
to the left), pitch (positive looking up) and roll (positive clockwise seen from behind) in degrees. The usual mount is
0.16 m ahead, 0.25 m up, tilted 20 degrees up. The design fixes the rest: pipeline 0, AprilTags, the fixed tags of the field
the robot stands on (`field`), 1280x960. **The mount of Hornet's camera and its 1280x960 pipeline are estimates**: Hornet's
repository does not say.

Measured with the tests (Hornet's camera, 1280x960, in front of the DECODE blue goal tag 20, converted as Hornet's `Camera`
subsystem converts): at 1.5 m the position error has median 0.9 cm and maximum 2.8 cm, heading 99th percentile 0.9 degrees;
at 3 m median 6 cm, maximum 20 cm, heading median 1 degree. Driving at 1.6 m/s the pose trails the robot by about 90 ms.

## Not simulated

Lens distortion and calibration, rolling shutter, motion blur, exposure and lighting, detector failures other than size,
angle and occlusion, the camera's boot time, mount calibration error, tag print and placement error, colour/neural/barcode/
Python pipelines, snapshots and recordings, uploads of pipelines, field maps and Python, the hardware report and calibration
files, the camera's own IMU modes. These requests fail the way a refused request fails (`false` or an invalid object) and are
recorded as unsupported uses in the problem list.

## Known gaps

- Single-tag flips at 3 m and beyond give metre-scale errors now and then; their rate depends on the pixel noise estimate.
- Whether a real camera reports `v` 1 for tags absent from its map is not verified (the model does).
- Occlusion rays use the world at the processing step, not at the capture, which differs for fast-moving blockers.
- The real camera's frame times are not on an exact grid; the model's are, with deterministic jitter in `cl` and `tl`.
