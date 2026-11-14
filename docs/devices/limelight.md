# Limelight 3A

Robot code runs the SDK's own `Limelight3A`, `LLResult`, `LLStatus` ... unchanged (SDK 11.1.0 and 12.0.0 ship the
same Limelight sources). The real driver polls `http://<ip>:5807/...` from its own thread; the simulator opens no sockets
and creates no threads. `SimLimelight3A` (sdk-common) replaces the driver's state machine and runs the SDK's own JSON
parsing on the text that `LimelightModel` (sim-hardware, `dev.ftcsim.hardware.camera`) returns for `GET /results` and
`/status`, so field semantics and SDK differences are the real ones. Between them is `CameraWire`
(sim-contracts), a plain HTTP-level port: `connect`, `get`, `post`, `delete` with simulated time, answered
`Reply(status, body, latency)`. A camera type is added with one line in `CameraModels.standard()`.

## From the world to a pose

1. **Frames.** Every 20 ms (640x480) or 50 ms (1280x960) the sensor takes a frame. After the capture latency `cl` the pipeline
   works on it with the chassis pose and the poses of the bodies tags are stuck on as they were **at the capture**
   (ring of 96 physics steps, interpolated), and finishes after the targeting latency `tl`. A request returns the newest
   result finished by that instant; before the first one, a valid empty document (`v` 0). `ts` is on the simulated clock.
   Results are computed from physics steps only, never from the poll, so slicing simulated time changes nothing.
2. **Visibility.** A tag is found if all four corners are in front of the lens and inside the image, it faces the camera
   (within 80 degrees of its normal), its shortest image side is at least 24 px, and rays from the lens to its middle and
   its four corners meet nothing nearer than the tag (`RayView`; the tag's own host body and a surface within 3 cm of the
   tag, such as the panel it is stuck on, do not count). Tags on tipping bodies (BIOBUZZ hives) follow the body.
3. **Observation.** The corners are projected through a pinhole with the published field of view, moved by
   deterministic noise (sigma 0.2 px, keyed by seed, frame, tag, corner, axis) and rounded to 0.25 px.
4. **Estimation.** The pose is *estimated* from the pixels and the camera's map, not copied: a closed-form planar guess
   (two candidates per tag, as OpenCV's IPPE) refined by Levenberg-Marquardt over all mapped tags at once (MegaTag 1,
   `botpose`, with `botpose_tagcount/_span/_avgdist/_avgarea`, `stdev_mt1`). With the yaw sent by `updateRobotOrientation`
   the rotation is fixed and only the position is fitted (MegaTag 2, `botpose_orb`, zero until a yaw arrived). A lone tag
   seen head-on from far can flip to its mirror pose, as on the real camera (MegaTag 2 exists to avoid that).
   `botpose` is the pose of the chassis origin in the FTC field frame (metres, degrees, 0 yaw = facing +X).
5. **What the camera believes.** Each pipeline's `fieldMap` is its belief, not the running field's: `decode` or `biobuzz`
   (that game's official fixed tags), `field` (the running field's fixed tags) or `none`. A tag it sees but its map lacks
   is in `Fiducial[]` (`v` 1, camera-relative arrays) and adds nothing to `botpose`, which stays zero with count 0.
6. **The driver.** `start/stop/pause`, `setPollRateHz` (1..250, ignored while running), `getTimeSinceLastUpdate` and
   `isConnected` (250 ms) follow the SDK's `scheduleAtFixedRate` grid in virtual time; a request advances the robot's
   clock by its latency (3 ms GET, 8 ms POST; an unanswered one by the SDK's 100 ms connect timeout). A name the profile
   does not list is an unplugged camera: not connected, never valid, and a problem is reported.

## Numbers and where they come from

| Value | Source |
|---|---|
| Sensor OV5647, FOV 54.5 x 42 degrees, 640x480 at 50 fps and 1280x960 at 20 fps for AprilTags | Limelight 3A quick-start, docs.limelightvision.io/docs/docs-limelight/getting-started/limelight-3a |
| JSON keys and layout (`v`, `pID`, `tl`, `cl`, `ts`, `tx`, `ty`, `ta`, `botpose*`, `Fiducial[]` ...) | docs.limelightvision.io/docs/docs-limelight/apis/json-results-specification |
| botpose in the FTC field frame, camera mounted relative to the robot's centre | .../apis/ftc-programming; MegaTag 2 yaw input: .../pipeline-apriltag/apriltag-robot-localization-megatag2 |
| `cl` 37.6 ms at 1280x960 | the specification's examples (37.62); their pixel values reach 822, so those frames were wider than 640 px |
| `tl` 18.8 ms + 1 ms per tag at 1280x960 | the one-tag example (19.78 ms); the per-tag part is **estimated** |
| 640x480: `cl` 12 ms, `tl` 6 ms + 1 ms per tag, jitter +-0.3 ms | **estimates** |
| pixel noise 0.2 px, quantum 0.25 px, minimum side 24 px, maximum view angle 80 degrees, 2 px edge margin | **estimates** |
| GET 3 ms, POST 8 ms, pipeline switch effective after 20 ms, first frame 7 ms after start, camera up 10 min at t=0 | **estimates** |
| intrinsics (centred principal point, focal length from the FOV, no distortion), `hwtype` 7, `fam` 36H11C | derived / example values |
| `ty` positive up | the specification's examples (the table says down but a target below the middle has `ty` < 0) |
| per-tag `ta` a fraction (0-1), top-level `ta` a percentage | the specification's tables |
| per-tag `t6c_ts`, `t6t_cs`, `t6r_ts`, `t6t_rs`: x right, y down, z into the tag (the AprilTag convention) | **assumed**; only the depth in `t6t_cs` and `t6r_fs` (= `botpose` for one tag) are checked |

## Profile

```json
"cameras": { "limelight": {                       // the name robot code asks the hardware map for
  "type": "limelight-3a",
  "mount": { "x": 0.16, "y": 0.0, "z": 0.25, "yawDegrees": 0, "pitchDegrees": 20, "rollDegrees": 0 },
  "pipelines": { "0": { "type": "apriltag", "fieldMap": "decode", "ids": [20, 24], "resolution": "1280x960", "sendCorners": false } } } }
```

The mount is in the chassis frame like every point of a profile (X forward, Y left, Z up from the centre of the
chassis box, about 0.18 m above the floor for the generic chassis); yaw is positive to the left, pitch positive looking up,
roll positive clockwise seen from behind. `type`, `fieldMap` are required; `ids` filters, `resolution` defaults to
`640x480`. A pipeline slot that is not listed finds nothing and is reported. **Hornet's mount (0.16 m ahead, 0.25 m up, 20
degrees up) and its 1280x960 pipeline are estimates**: the profile has no notes field, and Hornet's repository does not say.
The integrator builds the cameras with `RobotCameras.build(profile, deviceContext, seed)` and passes the `CameraRack`
(a `CameraWire`) in `RobotEnvironment`; `CameraRack.problems()` joins the problem list.

## Measured with the tests

Hornet's camera, 1280x960, a robot standing in front of the DECODE blue goal tag (20), converted as Hornet converts: at 1.5 m
the position error has median 0.9 cm and maximum 2.8 cm, heading 99th percentile 0.9 degrees; at 3 m median 6 cm,
maximum 20 cm, heading median 1 degree. Driving at 1.6 m/s the pose trails the robot by about 90 ms.

## Not simulated

Lens distortion and the camera's calibration, rolling shutter, motion blur, exposure and lighting, detector failures
other than size, angle and occlusion, the camera's boot time, mount calibration error, tag print and placement error,
colour/neural/barcode/Python pipelines, snapshots and recordings, uploads of pipelines, field maps and Python, the
hardware report and calibration files, the camera's own IMU modes. These requests fail the way a refused request fails
(`false` or an invalid object) and are recorded as unsupported uses in the problem list.

## Known gaps

- Single-tag flips at 3 m and beyond give metre-scale errors now and then; their rate depends on the pixel noise estimate.
- Whether a real camera reports `v` 1 for tags absent from its map is not verified (the model does).
- Occlusion rays use the world at the processing step, not at the capture, which differs for fast-moving blockers.
- The real camera's frame times are not on an exact grid; the model's are, with deterministic jitter in `cl` and `tl`.
