package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.hardware.CameraWire.Reply;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A Limelight 3A running AprilTag pipelines, as the SDK's {@code Limelight3A} driver sees it over HTTP: it answers
 * {@code /results} and {@code /status}, switches pipelines and takes the robot's yaw for MegaTag 2, and refuses, and
 * reports as a problem, everything that needs the real camera's storage or software.
 *
 * <p><b>What it does.</b> Its sensor takes a frame every 20 ms at 640 x 480 (50 per second) or every 50 ms at
 * 1280 x 960 (20 per second). After the capture latency {@code cl} the pipeline looks at the frame, with the chassis
 * and the tags' hosts where they were when it was taken: every tag that is in front of the camera, faces it, lies
 * wholly in the image, is at least {@value TagSight#MIN_SIDE_PIXELS} pixels across its shortest side and has nothing
 * in the way is found, its corners moved by the sensor's noise and rounded to a quarter of a pixel. The robot's pose
 * is then estimated from those pixels alone ({@link PoseSolver}, over the tags that the pipeline's field map knows),
 * so range, tag size, angle and pixel size limit its accuracy as on the real camera. The result is available after
 * the targeting latency {@code tl}; a request returns the newest result that is available then.
 *
 * <p><b>What the camera believes.</b> Each pipeline's field map is the camera's belief about where tags are, whichever
 * field the robot stands on: the official fixed tags of a game ({@code decode}, {@code biobuzz}), the running field's own
 * fixed tags ({@code field}), or none. A tag the camera sees but its map lacks is reported, with the pose of the
 * camera relative to it, and the result is valid; it just adds nothing to {@code botpose}, which stays zero when
 * no mapped tag is seen, as in the specification's examples for pipelines that give no pose.
 *
 * <p><b>Profile settings</b> (flat names, numbers and text): {@code mount.x}, {@code mount.y}, {@code mount.z} in
 * metres in the robot's frame (X forward, Y left, Z up, from the chassis reference), {@code mount.yawDegrees},
 * {@code mount.pitchDegrees} (positive looks up) and {@code mount.rollDegrees} (all default 0); then for each
 * pipeline slot N in 0..9 that the camera has configured: {@code pipelineN.type} ({@code apriltag}),
 * {@code pipelineN.fieldMap}, {@code pipelineN.ids} (a comma-separated filter, default every tag),
 * {@code pipelineN.resolution} ({@code 640x480} or {@code 1280x960}, default the first) and
 * {@code pipelineN.sendCorners} (0 or 1, default 0, whether the corners are in the results). A slot that is not
 * configured runs Limelight's default colour pipeline, which finds nothing here.
 *
 * <p>Estimates, which no measurement of a real unit backs: the latencies (see {@link Resolution}), the corner noise
 * ({@value #CORNER_SIGMA_PIXELS} px, plus rounding to a quarter of a pixel), the decodable size and viewing angle (see
 * {@link TagSight}), the request latencies and the pipeline-switch delay. The camera was running before the
 * session began.
 */
public final class LimelightModel implements CameraModel {

    /** The registry's factory. */
    public static final CameraFactory FACTORY = new CameraFactory() {
        @Override
        public CameraModel create(DeviceConfig config, DeviceContext context, long seed) {
            return LimelightModel.create(config, context, seed);
        }

        @Override
        public void check(DeviceConfig config) {
            read(config);
        }
    };

    /**
     * The standard deviation of the error of a detected corner before it is rounded, in pixels. Estimate: the AprilTag
     * detector fits lines to the edge pixels of each side, which puts its corners well below a pixel; 0.1 to 0.3 px
     * is the range reported for good conditions.
     */
    static final double CORNER_SIGMA_PIXELS = 0.2;
    /** Corners come out rounded to this many pixels: the resolution of the detector's sub-pixel refinement. Estimate. */
    static final double QUANTUM_PIXELS = 0.25;
    static final int PIPELINES = 10;
    /** A tag family of 36h11 has 587 codes. */
    static final int TAG_IDS = 587;

    /** A GET over the USB network takes about this long: a round trip and the camera's web server. Estimate. */
    static final long GET_LATENCY_NANOS = 3_000_000L;
    /** A POST takes longer, as the camera acts on it first. Estimate. */
    static final long POST_LATENCY_NANOS = 8_000_000L;
    /** A pipeline switch takes effect for the frames captured after this long. Estimate. */
    static final long SWITCH_DELAY_NANOS = 20_000_000L;
    static final long FIRST_FRAME_DELAY_NANOS = 7_000_000L;

    private static final int KEPT_FRAMES = 12;
    private static final int HISTORY_STEPS = 96;
    private static final double LATENCY_JITTER_MILLIS = 0.3;
    /** No capture latency is shorter than this, so by then everything that can change how a frame is taken is known. */
    private static final long DECISION_LEAD_NANOS = 10_000_000L;

    private static final Map<String, String> REFUSED = refused();

    /** The settings of one pipeline slot. */
    record PipelineSpec(int index, String fieldMap, Set<Integer> ids, Resolution resolution, boolean sendCorners) {}

    /** What a profile says about one camera. */
    record Settings(CameraMount mount, Map<Integer, PipelineSpec> pipelines) {}

    private record Pipeline(PipelineSpec spec, Map<Integer, SceneTag> map) {}

    /** A frame the camera has decided to take, and what it takes: when it is processed is known from the start. */
    private record Pending(
            long index, long captureNanos, long startNanos, int pipelineIndex, Pipeline pipeline, Resolution resolution) {}

    private final RayView rays;
    private final CameraMount mount;
    private final Rigid cameraInRobot;
    private final Map<Integer, Pipeline> pipelines;
    private final List<SceneTag> scene;
    private final BodyView chassis;
    private final BodyView[] hostViews;
    private final Map<Integer, Integer> hostBodies;
    private final PoseHistory history;
    private final PixelNoise noise;
    private final UsageLog refusals = new UsageLog();
    private final List<String> setupProblems = new ArrayList<>();
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Deque<long[]> switches = new ArrayDeque<>();
    private final Deque<double[]> orientations = new ArrayDeque<>();
    private final Map<Resolution, Pinhole> optics = new TreeMap<>();
    private final double pixelSigma = Math.sqrt(CORNER_SIGMA_PIXELS * CORNER_SIGMA_PIXELS + QUANTUM_PIXELS * QUANTUM_PIXELS / 12.0);

    private int activePipeline;
    private long nextIndex;
    private long nextCapture;
    private Pending pending;
    private Frame cachedFrame;
    private String cachedJson;

    private LimelightModel(
            DeviceContext context, Settings settings, Map<Integer, Pipeline> pipelines, List<SceneTag> scene, long seed) {
        this.rays = context.rays();
        this.mount = settings.mount();
        this.cameraInRobot = mount.cameraInRobot();
        this.pipelines = pipelines;
        this.noise = new PixelNoise(seed);
        this.chassis = context.body();

        Map<Integer, Integer> bodies = new LinkedHashMap<>();
        List<BodyView> views = new ArrayList<>();
        List<SceneTag> visible = new ArrayList<>();
        for (SceneTag tag : scene) {
            if (tag.isFixed()) {
                visible.add(tag);
                continue;
            }
            Integer body = bodies.get(tag.hostEntityId());
            if (body == null) {
                try {
                    views.add(context.entity(tag.hostEntityId()));
                    body = views.size();
                    bodies.put(tag.hostEntityId(), body);
                } catch (IllegalArgumentException unknown) {
                    setupProblems.add("tag " + tag.id() + " is stuck on entity " + tag.hostEntityId()
                            + ", which the host gives devices no view of, so the camera never sees it");
                    continue;
                }
            }
            visible.add(tag);
        }
        this.scene = List.copyOf(visible);
        this.hostBodies = Map.copyOf(bodies);
        this.hostViews = views.toArray(new BodyView[0]);
        this.history = new PoseHistory(HISTORY_STEPS, 1 + hostViews.length);
        this.history.record(context.nowNanos(), sample());
        this.nextCapture = context.nowNanos() + FIRST_FRAME_DELAY_NANOS;
    }

    // ---------------------------------------------------------------------------------------------
    // Building it from a profile
    // ---------------------------------------------------------------------------------------------

    private static LimelightModel create(DeviceConfig config, DeviceContext context, long seed) {
        Settings settings = read(config);
        Map<Integer, Pipeline> pipelines = new TreeMap<>();
        for (PipelineSpec spec : settings.pipelines().values()) {
            pipelines.put(spec.index(), new Pipeline(spec, mapOf(config, context, spec)));
        }
        List<SceneTag> scene = new ArrayList<>();
        context.fieldTags().forEach(tag -> scene.add(SceneTag.of(tag)));
        LimelightModel model = new LimelightModel(context, settings, pipelines, scene, seed);
        context.onStep(model::afterStep);
        return model;
    }

    private static Map<Integer, SceneTag> mapOf(DeviceConfig config, DeviceContext context, PipelineSpec spec) {
        List<FieldTag> tags;
        switch (spec.fieldMap()) {
            case "none" -> tags = List.of();
            case "field" -> tags = context.fieldTags().stream().filter(tag -> tag.hostEntityId() == -1).toList();
            default -> {
                try {
                    tags = context.gameMap(spec.fieldMap());
                } catch (IllegalArgumentException unknown) {
                    throw config.rejected("pipeline" + spec.index() + ".fieldMap", "names a map the host cannot supply: " + unknown.getMessage());
                }
            }
        }
        Map<Integer, SceneTag> map = new TreeMap<>();
        for (FieldTag tag : tags) {
            map.putIfAbsent(tag.id(), SceneTag.of(tag));
        }
        return map;
    }

    /** Reads and checks the settings; nothing here needs a world. */
    static Settings read(DeviceConfig config) {
        double pitch = config.number("mount.pitchDegrees", 0);
        if (Math.abs(pitch) > 90) {
            throw config.rejected("mount.pitchDegrees", "must be within -90 and 90 degrees, not " + pitch);
        }
        CameraMount mount = new CameraMount(
                config.number("mount.x"),
                config.number("mount.y"),
                config.number("mount.z"),
                config.number("mount.yawDegrees", 0),
                pitch,
                config.number("mount.rollDegrees", 0));
        Map<Integer, PipelineSpec> pipelines = new TreeMap<>();
        for (int index = 0; index < PIPELINES; index++) {
            if (config.hasGroup("pipeline" + index)) {
                pipelines.put(index, readPipeline(config, index));
            }
        }
        return new Settings(mount, pipelines);
    }

    private static PipelineSpec readPipeline(DeviceConfig config, int index) {
        String prefix = "pipeline" + index + ".";
        String type = config.text(prefix + "type");
        if (!type.equals("apriltag")) {
            throw config.rejected(prefix + "type", "must be \"apriltag\", not \"" + type
                    + "\"; colour, neural, barcode and Python pipelines are not simulated");
        }
        String fieldMap = config.text(prefix + "fieldMap").strip();
        if (fieldMap.isEmpty()) {
            throw config.rejected(prefix + "fieldMap", "must name a game, \"field\" or \"none\"");
        }
        Set<Integer> ids = new TreeSet<>();
        if (config.has(prefix + "ids")) {
            for (String part : config.text(prefix + "ids").split(",")) {
                int id;
                try {
                    id = Integer.parseInt(part.strip());
                } catch (NumberFormatException notANumber) {
                    throw config.rejected(prefix + "ids", "must be tag ids separated by commas, not \"" + part.strip() + "\"");
                }
                if (id < 0 || id >= TAG_IDS) {
                    throw config.rejected(prefix + "ids", "has the id " + id + "; the tag family has ids 0.." + (TAG_IDS - 1));
                }
                ids.add(id);
            }
        }
        Resolution resolution = Resolution.of(config.text(prefix + "resolution", "640x480"));
        if (resolution == null) {
            throw config.rejected(prefix + "resolution", "must be one of " + Resolution.labels());
        }
        int corners = config.integer(prefix + "sendCorners", 0);
        if (corners != 0 && corners != 1) {
            throw config.rejected(prefix + "sendCorners", "must be 0 or 1, not " + corners);
        }
        return new PipelineSpec(index, fieldMap, Set.copyOf(ids), resolution, corners == 1);
    }

    private static Map<String, String> refused() {
        Map<String, String> refused = new LinkedHashMap<>();
        String hardware = "the camera's hardware report and calibration data are not simulated";
        String stored = "pipelines stored on the camera cannot be read, uploaded or changed in the simulator";
        String snapshots = "snapshots, recordings and SnapScripts need the camera's storage, which is not simulated";
        String python = "Python SnapScript pipelines are not simulated";
        String settings = "the camera's own settings (IMU mode, throttle, field maps, neural networks) are not simulated";
        for (String path : new String[] {"/hwreport", "/cal-default", "/cal-file", "/cal-eeprom", "/cal-latest", "/cal-pointcloud", "/cal-stats"}) {
            refused.put("GET " + path, hardware);
        }
        refused.put("GET /pipeline-default", stored);
        refused.put("GET /pipeline-atindex", stored);
        refused.put("GET /getsnapscriptnames", snapshots);
        refused.put("GET /snapshotmanifest", snapshots);
        refused.put("GET /videolist", snapshots);
        refused.put("GET /recording-list", snapshots);
        refused.put("POST /update-pipeline", stored);
        refused.put("POST /upload-pipeline", stored);
        refused.put("POST /upload-fieldmap", settings);
        refused.put("POST /upload-python", python);
        refused.put("POST /update-pythoninputs", python);
        refused.put("POST /upload-nn", settings);
        refused.put("POST /upload-nnlabels", settings);
        refused.put("POST /update-imumode", settings);
        refused.put("POST /update-throttle", settings);
        refused.put("POST /update-imuassistalpha", settings);
        refused.put("POST /capture-snapshot", snapshots);
        refused.put("POST /upload-snapshot", snapshots);
        refused.put("POST /recording-flush", snapshots);
        refused.put("POST /cal-file", hardware);
        refused.put("POST /cal-eeprom", hardware);
        for (String path : new String[] {"/delete-snapshots", "/delete-snapshot", "/delete-video", "/delete-videos"}) {
            refused.put("DELETE " + path, snapshots);
        }
        for (String path : new String[] {"/cal-latest", "/cal-eeprom", "/cal-file"}) {
            refused.put("DELETE " + path, hardware);
        }
        return refused;
    }

    // ---------------------------------------------------------------------------------------------
    // The HTTP side
    // ---------------------------------------------------------------------------------------------

    @Override
    public Reply get(String target, long nowNanos) {
        switch (pathOf(target)) {
            case "/results" -> {
                return Reply.json(resultsAt(nowNanos), GET_LATENCY_NANOS);
            }
            case "/status" -> {
                Pipeline pipeline = pipelines.get(activePipeline);
                Resolution resolution = pipeline == null ? Resolution.VGA : pipeline.spec().resolution();
                return Reply.json(
                        LimelightJson.status("limelight", activePipeline, pipeline != null, resolution.framesPerSecond(), nextIndex),
                        GET_LATENCY_NANOS);
            }
            default -> {
                return refuse("GET", target, nowNanos, GET_LATENCY_NANOS);
            }
        }
    }

    @Override
    public Reply post(String target, String body, long nowNanos) {
        long arrival = nowNanos + POST_LATENCY_NANOS / 2;
        switch (pathOf(target)) {
            case "/pipeline-switch" -> {
                Integer index = integerParameter(target, "index");
                if (index == null || index < 0 || index >= PIPELINES) {
                    return Reply.status(400, POST_LATENCY_NANOS);
                }
                switches.addLast(new long[] {arrival + SWITCH_DELAY_NANOS, index});
                if (!pipelines.containsKey(index)) {
                    refusals.note("pipeline " + index + " is not configured, so the camera finds nothing in it", nowNanos);
                }
                return Reply.status(Reply.OK, POST_LATENCY_NANOS);
            }
            case "/reload-pipeline" -> {
                return Reply.status(Reply.OK, POST_LATENCY_NANOS);
            }
            case "/update-robotorientation" -> {
                double[] values = JsonNumbers.parseArray(body);
                if (values == null || values.length == 0) {
                    return Reply.status(400, POST_LATENCY_NANOS);
                }
                orientations.addLast(new double[] {arrival, values[0]});
                return Reply.status(Reply.OK, POST_LATENCY_NANOS);
            }
            default -> {
                return refuse("POST", target, nowNanos, POST_LATENCY_NANOS);
            }
        }
    }

    @Override
    public Reply delete(String target, long nowNanos) {
        return refuse("DELETE", target, nowNanos, POST_LATENCY_NANOS);
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>(setupProblems);
        problems.addAll(refusals.lines(""));
        return problems;
    }

    private Reply refuse(String method, String target, long nowNanos, long latency) {
        String reason = REFUSED.get(method + " " + pathOf(target));
        if (reason == null) {
            refusals.note(method + " " + pathOf(target) + " is not a request the camera serves", nowNanos);
            return Reply.status(404, latency);
        }
        refusals.note(method + " " + pathOf(target) + ": " + reason, nowNanos);
        return Reply.status(501, latency);
    }

    private static String pathOf(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static Integer integerParameter(String target, String name) {
        int query = target.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String pair : target.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                try {
                    return Integer.valueOf(pair.substring(equals + 1));
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The newest result the camera had finished by the instant, as the JSON the camera serves. */
    private String resultsAt(long nowNanos) {
        Frame newest = null;
        for (Iterator<Frame> it = frames.descendingIterator(); it.hasNext(); ) {
            Frame frame = it.next();
            if (frame.completionNanos() <= nowNanos) {
                newest = frame;
                break;
            }
        }
        if (newest == null) {
            return LimelightJson.nothingYet(activePipeline, nowNanos, cameraPose());
        }
        if (newest != cachedFrame) {
            cachedFrame = newest;
            cachedJson = LimelightJson.results(newest, cameraPose());
        }
        return cachedJson;
    }

    /** The camera's pose in the robot's frame as {@code t6c_rs} reports it: x, y, z, roll, pitch, yaw. */
    private double[] cameraPose() {
        return new double[] {mount.x(), mount.y(), mount.z(), mount.rollDegrees(), mount.pitchDegrees(), mount.yawDegrees()};
    }

    // ---------------------------------------------------------------------------------------------
    // The camera's own time: frames
    // ---------------------------------------------------------------------------------------------

    private BodyPose[] sample() {
        BodyPose[] poses = new BodyPose[1 + hostViews.length];
        poses[0] = chassis.sample();
        for (int i = 0; i < hostViews.length; i++) {
            poses[i + 1] = hostViews[i].sample();
        }
        return poses;
    }

    /** After a physics step: remember where everything is, and process the frames whose processing time has come. */
    void afterStep(long stepNanos) {
        history.record(stepNanos, sample());
        for (;;) {
            if (pending == null) {
                if (nextCapture + DECISION_LEAD_NANOS > stepNanos) {
                    return;
                }
                pending = decide(nextCapture);
            }
            if (pending.startNanos() > stepNanos) {
                return;
            }
            frames.addLast(process(pending));
            while (frames.size() > KEPT_FRAMES) {
                frames.removeFirst();
            }
            nextCapture = pending.captureNanos() + pending.resolution().framePeriodNanos();
            nextIndex++;
            pending = null;
        }
    }

    private Pending decide(long captureNanos) {
        while (!switches.isEmpty() && switches.peekFirst()[0] <= captureNanos) {
            activePipeline = (int) switches.pollFirst()[1];
        }
        Pipeline pipeline = pipelines.get(activePipeline);
        Resolution resolution = pipeline == null ? Resolution.VGA : pipeline.spec().resolution();
        double jitter = LATENCY_JITTER_MILLIS * Math.max(-2.0, Math.min(2.0, noise.gaussian(nextIndex, -1, 0, 0)));
        long latency = Math.round((resolution.captureLatencyMillis() + jitter) * 1e6);
        return new Pending(nextIndex, captureNanos, captureNanos + latency, activePipeline, pipeline, resolution);
    }

    private Frame process(Pending job) {
        Resolution resolution = job.resolution();
        Pinhole camera = optics.computeIfAbsent(resolution, Resolution::camera);
        List<Frame.Reading> readings = new ArrayList<>();
        double[] botpose = new double[6];
        double[] stdevMt1 = new double[6];
        double[] botposeOrb = new double[6];
        double[] stdevMt2 = new double[6];
        int tagCount = 0;
        double span = 0;
        double averageDistance = 0;
        double averageArea = 0;
        if (job.pipeline() != null) {
            List<Sighting> sightings = sightings(job, camera);
            sightings.sort(Comparator.comparingDouble((Sighting s) -> -s.area).thenComparingInt(s -> s.tag.id()));
            PoseSolver solver = new PoseSolver(camera, cameraInRobot);
            PoseSolver soloSolver = new PoseSolver(camera, Rigid.IDENTITY);
            List<PoseSolver.Observation> mapped = new ArrayList<>();
            List<Rigid> mappedPoses = new ArrayList<>();
            List<Double> mappedHalves = new ArrayList<>();
            List<SceneTag> mappedTags = new ArrayList<>();
            List<Double> mappedAreas = new ArrayList<>();
            for (Sighting s : sightings) {
                SceneTag believed = job.pipeline().map().get(s.tag.id());
                readings.add(reading(camera, soloSolver, s, believed));
                if (believed != null) {
                    mapped.add(new PoseSolver.Observation(believed.fieldCorners(Rigid.IDENTITY), s.pixels));
                    mappedPoses.add(believed.inHost());
                    mappedHalves.add(0.5 * believed.sizeMeters());
                    mappedTags.add(believed);
                    mappedAreas.add(s.area);
                }
            }
            if (!mapped.isEmpty()) {
                PoseSolver.Fit fit = solver.fit(mapped, mappedPoses, mappedHalves, pixelSigma);
                if (fit != null) {
                    botpose = pose(fit.robot());
                    stdevMt1 = fit.sigma();
                    tagCount = mapped.size();
                    Vec3 eye = fit.robot().compose(cameraInRobot).translation();
                    for (int i = 0; i < tagCount; i++) {
                        Vec3 centre = mappedTags.get(i).inHost().translation();
                        averageDistance += centre.minus(eye).length() / tagCount;
                        averageArea += 100.0 * mappedAreas.get(i) / tagCount;
                        for (int j = i + 1; j < tagCount; j++) {
                            span = Math.max(span, centre.minus(mappedTags.get(j).inHost().translation()).length());
                        }
                    }
                }
                Double yaw = orientationAt(job.startNanos());
                if (yaw != null) {
                    PoseSolver.Fit orb = solver.fitPosition(mapped, Mat3.rotZ(StrictMath.toRadians(yaw)), pixelSigma);
                    if (orb != null) {
                        botposeOrb = new double[] {
                            orb.robot().translation().x(), orb.robot().translation().y(), orb.robot().translation().z(), 0, 0, yaw
                        };
                        stdevMt2 = orb.sigma();
                    }
                }
            }
        }
        double jitter = LATENCY_JITTER_MILLIS * Math.max(-2.0, Math.min(2.0, noise.gaussian(job.index(), -2, 0, 0)));
        double targeting = resolution.targetingMillis(readings.size()) + jitter;
        long completion = job.startNanos() + Math.round(targeting * 1e6);
        return new Frame(
                job.index(), job.captureNanos(), job.startNanos(), completion,
                (job.startNanos() - job.captureNanos()) / 1e6, targeting,
                job.pipelineIndex(), job.pipeline() != null, job.pipeline() != null && job.pipeline().spec().sendCorners(),
                camera.width(), camera.height(), List.copyOf(readings),
                botpose, stdevMt1, tagCount, span, averageDistance, averageArea, botposeOrb, stdevMt2);
    }

    /** What the pipeline found: the ideal geometry decides which tags it finds, and the sensor decides where their corners are. */
    private static final class Sighting {
        final SceneTag tag;
        final double[] pixels;
        final double area;

        Sighting(SceneTag tag, double[] pixels, double area) {
            this.tag = tag;
            this.pixels = pixels;
            this.area = area;
        }
    }

    private List<Sighting> sightings(Pending job, Pinhole camera) {
        Rigid cameraInField = history.at(0, job.captureNanos()).compose(cameraInRobot);
        Vec3 eye = cameraInField.translation();
        Set<Integer> filter = job.pipeline().spec().ids();
        List<Sighting> sightings = new ArrayList<>();
        for (SceneTag tag : scene) {
            if (!filter.isEmpty() && !filter.contains(tag.id())) {
                continue;
            }
            Rigid host = tag.isFixed() ? Rigid.IDENTITY : history.at(hostBodies.get(tag.hostEntityId()), job.captureNanos());
            TagSight.Look look = TagSight.look(camera, cameraInField, tag, host);
            if (look.visibility() != TagSight.Visibility.VISIBLE || TagSight.blocked(rays, eye, tag, look)) {
                continue;
            }
            double[] pixels = new double[8];
            for (int i = 0; i < 4; i++) {
                for (int axis = 0; axis < 2; axis++) {
                    double value = look.pixels()[2 * i + axis] + CORNER_SIGMA_PIXELS * noise.gaussian(job.index(), tag.id(), i, axis);
                    double limit = axis == 0 ? camera.width() : camera.height();
                    pixels[2 * i + axis] = Math.max(0.0, Math.min(limit, Math.rint(value / QUANTUM_PIXELS) * QUANTUM_PIXELS));
                }
            }
            sightings.add(new Sighting(tag, pixels, area(pixels) / ((double) camera.width() * camera.height())));
        }
        return sightings;
    }

    /** One tag as the pipeline reports it, from the corners it found. */
    private Frame.Reading reading(Pinhole camera, PoseSolver soloSolver, Sighting sighting, SceneTag believed) {
        double[] pixels = sighting.pixels;
        double[] centre = centre(pixels);
        double[] zeros = new double[6];
        double[] cameraInTarget = zeros;
        double[] robotInField = zeros;
        double[] robotInTarget = zeros;
        double[] targetInCamera = zeros;
        double[] targetInRobot = zeros;
        double size = believed != null ? believed.sizeMeters() : sighting.tag.sizeMeters();
        double[] corners = new double[12];
        Vec3[] local = SceneTag.corners(size);
        for (int i = 0; i < 4; i++) {
            corners[3 * i] = local[i].x();
            corners[3 * i + 1] = local[i].y();
            corners[3 * i + 2] = local[i].z();
        }
        PoseSolver.Fit solo = soloSolver.fit(
                List.of(new PoseSolver.Observation(corners, pixels)), List.of(Rigid.IDENTITY), List.of(0.5 * size), pixelSigma);
        if (solo != null) {
            Rigid tagInCameraFrame = solo.robot().inverse();
            // Limelight's target space has X to the right, Y down and Z into the tag, as the AprilTag library's does.
            Rigid cameraFromTarget = tagInCameraFrame.compose(new Rigid(new Mat3(1, 0, 0, 0, -1, 0, 0, 0, -1), Vec3.ZERO));
            targetInCamera = pose(cameraFromTarget);
            cameraInTarget = pose(cameraFromTarget.inverse());
            robotInTarget = pose(cameraFromTarget.inverse().compose(cameraInRobot.inverse()));
            targetInRobot = pose(cameraInRobot.compose(cameraFromTarget));
            if (believed != null) {
                robotInField = pose(believed.inHost().compose(solo.robot()).compose(cameraInRobot.inverse()));
            }
        }
        return new Frame.Reading(
                sighting.tag.id(), believed != null, pixels, centre[0], centre[1],
                camera.horizontalDegrees(centre[0]), camera.verticalDegrees(centre[1]), sighting.area,
                cameraInTarget, robotInField, robotInTarget, targetInCamera, targetInRobot);
    }

    /** The yaw robot code last told the camera, as of the instant, or null if it has not told it any. */
    private Double orientationAt(long nanos) {
        while (orientations.size() > 1) {
            Iterator<double[]> updates = orientations.iterator();
            updates.next();
            if (updates.next()[0] > nanos) {
                break;
            }
            orientations.removeFirst();
        }
        double[] first = orientations.peekFirst();
        return first == null || first[0] > nanos ? null : first[1];
    }

    /** x, y, z in metres and roll, pitch, yaw in degrees. */
    static double[] pose(Rigid pose) {
        return new double[] {
            pose.translation().x(), pose.translation().y(), pose.translation().z(),
            StrictMath.toDegrees(pose.rotation().roll()),
            StrictMath.toDegrees(pose.rotation().pitch()),
            StrictMath.toDegrees(pose.rotation().yaw())
        };
    }

    /** Where the diagonals of the quadrilateral cross, which is where the tag's middle is in a pinhole image. */
    private static double[] centre(double[] p) {
        double d1x = p[4] - p[0];
        double d1y = p[5] - p[1];
        double d2x = p[6] - p[2];
        double d2y = p[7] - p[3];
        double denominator = d1x * d2y - d1y * d2x;
        if (Math.abs(denominator) < 1e-12) {
            return new double[] {0.25 * (p[0] + p[2] + p[4] + p[6]), 0.25 * (p[1] + p[3] + p[5] + p[7])};
        }
        double t = ((p[2] - p[0]) * d2y - (p[3] - p[1]) * d2x) / denominator;
        return new double[] {p[0] + t * d1x, p[1] + t * d1y};
    }

    private static double area(double[] p) {
        double twice = 0;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            twice += p[2 * i] * p[2 * next + 1] - p[2 * next] * p[2 * i + 1];
        }
        return Math.abs(twice) / 2.0;
    }
}
