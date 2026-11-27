# Limelight 3A

How the simulated Limelight 3A works, where its numbers come from and what is estimated: read it before trusting a vision pose.

Robot code runs the SDK's own `Limelight3A`, `LLResult` and `LLStatus` unchanged (SDK 11.1.0 and 12.0.0 ship the same
sources). The real driver polls `http://<ip>:5807/...` from its own thread; the simulator opens no sockets and no threads.
`SimLimelight3A` (sdk-common) replaces the driver's state machine and runs the SDK's own JSON parsing on the text that
`LimelightModel` (sim-hardware, `dev.ftcsim.hardware.camera`) returns for `GET /results` and `/status`; `CameraWire`
(sim-contracts) is the HTTP-level port between them, on simulated time. A new camera type is one line in
`CameraModels.standard()`. The Javadoc of `LimelightModel` holds the pipeline settings and the estimated numbers.

## From the world to a pose

- **Frames.** One every 20 ms (640x480) or 50 ms (1280x960), worked on after the capture latency `cl` from the chassis and tag
  host bodies **as they were at the capture** (96 physics steps kept), finished after the targeting latency `tl`. A request
  returns the newest finished result, or a valid empty one (`v` 0). Results come from physics steps, never from the poll.
- **Visibility.** Four corners in front of the lens and inside the image, the tag facing the camera, a long enough side (limits
  in the table), and nothing nearer on rays to its middle and corners (`RayView`; its own host body and a surface within 3 cm
  do not count). Tags on tipping bodies (BIOBUZZ hives) follow the body.
- **Estimation.** Corners go through a pinhole with the published field of view, plus deterministic noise and rounding. The pose
  is *estimated* from the pixels and the camera's map, not copied: MegaTag 1 (`botpose` and its `botpose_*`, `stdev_mt1`) and
  MegaTag 2 (`botpose_orb`, from the yaw sent by `updateRobotOrientation`; zero until one arrives; only the position is
  fitted). A lone tag seen head-on from far can flip to its mirror pose, as on the real camera. `botpose` is the chassis origin
  in the FTC field frame (metres, degrees, 0 = facing +X).
- **What the camera believes.** Each pipeline's `fieldMap` is its belief, not the running field's: `decode` or `biobuzz` (that
  game's official fixed tags), `field` (the running field's fixed tags) or `none`. A tag it sees but its map lacks appears in
  `Fiducial[]` (`v` 1) and adds nothing to `botpose` (zero, count 0).
- **The driver.** `setPollRateHz` (1..250, ignored while running), `getTimeSinceLastUpdate` and `isConnected` (250 ms) follow the
  SDK's `scheduleAtFixedRate` grid in virtual time. A request advances the robot's clock by its latency (an unanswered one by
  the SDK's 100 ms connect timeout).
- **Viewport.** A bright green outline marks each tag in the newest completed frame, using the same detections as
  `/results` (including tags absent from the camera's map). Highlights do not require robot code to poll the camera,
  follow tags on moving bodies, and disappear when a completed frame no longer detects them.

## Numbers and sources

URLs are under docs.limelightvision.io/docs/docs-limelight/.

| Value | Source |
|---|---|
| OV5647, FOV 54.5 x 42 degrees, 640x480 at 50 fps, 1280x960 at 20 fps | `getting-started/limelight-3a` |
| JSON keys and layout; botpose in the FTC field frame; MegaTag 2 yaw input | `apis/json-results-specification`, `apis/ftc-programming`, `pipeline-apriltag/apriltag-robot-localization-megatag2` |
| 1280x960: `cl` 37.6 ms, `tl` 18.8 ms | The specification's examples (37.62; 19.78 for one tag); their pixel values reach 822, so those frames were wider than 640 px. The 1 ms per tag is **estimated** |
| `ty` positive up; per-tag `ta` a fraction (0-1), top-level `ta` a percentage | The specification's examples and tables (its table says `ty` down, but a target below the middle has `ty` < 0) |
| Intrinsics: centred principal point, focal length from the FOV, no distortion | Derived |
| Per-tag `t6c_ts`, `t6t_cs`, `t6r_ts`, `t6t_rs`: x right, y down, z into the tag | **Assumed** (AprilTag convention); only the depth in `t6t_cs` and `t6r_fs` (= `botpose` for one tag) are checked |
| 640x480: `cl` 12 ms, `tl` 6 ms + 1 ms per tag, jitter +-0.3 ms | **Estimate** |
| Corner noise sigma 0.2 px, quantum 0.25 px, minimum tag side 24 px, maximum view angle 80 degrees, 2 px edge margin | **Estimate** |
| GET 3 ms, POST 8 ms, pipeline switch 20 ms, first frame 7 ms after start, camera up 10 min at t=0 | **Estimate** |

## Where it is

- **Binding.** `Limelight3A` under any name; the first name that asks is the robot's one camera. A second name gets an
  unplugged camera (never connected or valid) and a line in the session's problems. `RobotRig` plugs it in (a `CameraRack`)
  with the field's tags and their host bodies; `CameraRack.problems()` joins `SimulationSession.problems()`.
- **Mount**, the robot profile's `camera` (`RobotProfiles`; nothing a team sets): X forward, Y left, Z up in
  metres from the centre of the chassis's box (about 0.18 m above the floor), yaw (positive left), pitch (positive up) and roll
  (positive clockwise seen from behind) in degrees. Hornet's is 0.16 m ahead, 0.25 m up, yaw 0, pitch 20.
- **On the turret:** on the BIOBUZZ robot (its profile's `cameraOnTurret`) the camera turns with the turret about the turret's axis. The mount is then given with the turret facing forward and the camera's place in the world is the chassis's pose turned by the turret's yaw (`LimelightModel` setting `mount.on` = `chassis` or `turret`, `TurretPlatform`). Frames in flight keep the turret yaw they were taken with, as they keep the chassis pose. A botpose is then the turret's frame, not the chassis's (BIOBUZZ has no fixed tags, so it reports none). BIOBUZZ's mount is the team's calibrated one (5.435 in ahead of the turret's 3.522 in pivot, 16.969 in above the floor, pitch 41), see [fixed-robots](../robots/fixed-robots.md).
- **Fixed by the design:** pipeline 0, AprilTags, the running field's fixed tags (`field`), 1280x960.
- **Estimated:** Hornet's camera mount and 1280x960 pipeline (its repository does not say).

## Accuracy and gaps

Measured by the tests with Hornet's camera, 1280x960, in front of DECODE's blue goal tag 20, converted as Hornet's `Camera`
subsystem converts: at 1.5 m the position error has median 0.9 cm, maximum 2.8 cm, heading 99th percentile 0.9 degrees; at 3 m
median 6 cm, maximum 20 cm, heading median 1 degree. Driving at 1.6 m/s the pose trails the robot by about 90 ms.

- Single-tag flips at 3 m and beyond give metre-scale errors now and then; their rate depends on the pixel noise estimate.
- Whether a real camera reports `v` 1 for tags absent from its map is not verified (the model does).
- Occlusion rays use the world at the processing step, not the capture, which differs for fast-moving blockers.
- The real camera's frame times are not on an exact grid; the model's are, with deterministic jitter in `cl` and `tl`.

## Not simulated

Lens distortion and calibration, rolling shutter, motion blur, exposure and lighting, detector failures other than size, angle
and occlusion, boot time, mount calibration error, tag print and placement error, colour/neural/barcode/Python pipelines,
snapshots and recordings, uploads of pipelines, field maps and Python, the hardware report and calibration files, the camera's
IMU modes. These requests fail as a refused request does (`false` or an invalid object) and are listed as unsupported uses.
