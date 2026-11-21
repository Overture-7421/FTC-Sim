package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.game.biobuzz.BiobuzzIds;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.ui.render.FieldRenderer;
import dev.ftcsim.ui.render.OrbitCamera;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a driver sees: the real DECODE and BIOBUZZ fields with Hornet's chassis on them, drawn by the application's own
 * viewport renderer on a hidden OpenGL context into pictures below {@code build/screens}, and what that costs to draw.
 * Without an OpenGL 3.3 context the tests are skipped, not failed.
 */
@Timeout(900)
class RealFieldViewportTest {
    private static final long SECOND = 1_000_000_000L;
    private static final int PURPLE = 101;
    private static final int GREEN = 102;

    /** A session with Hornet on a field, and what the window is told about the robot. */
    private record Run(TestSessions.Handle handle, RobotInfo robot, StartPose start, Alliance alliance, Field field)
            implements AutoCloseable {
        SimulationSession session() {
            return handle.session;
        }

        @Override
        public void close() throws IOException {
            handle.close();
        }
    }

    private static ViewportShots openViewport() throws Exception {
        try {
            return ViewportShots.open();
        } catch (Exception | LinkageError failure) {
            return Assumptions.abort("no OpenGL 3.3 context here: " + failure);
        }
    }

    private static Run open(Field field, Alliance alliance, String startPrefix) throws IOException {
        RobotSpec profile = RobotDesign.decode().spec();
        StartPose start = field.description().startPoses().stream()
                .filter(pose -> pose.name().startsWith(startPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no start pose " + startPrefix + " in " + field.description().startPoses()));
        TestSessions.Handle handle = TestSessions.open(
                profile, TestSessions.program(), field, new FieldSetup(1, alliance), start.x(), start.y(), start.headingRadians());
        MecanumConfig box = profile.chassis();
        return new Run(handle, ViewportShots.robotInfo(profile.name(), box.lengthM(), box.widthM(), box.heightM()), start, alliance, field);
    }

    private static GamepadState pad(boolean a) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, a, false, false, false, false, false, false, false,
                false, false, false);
    }

    /** Settles the session for a moment and shows it to the viewport, with the camera set up as the Practice screen does. */
    private static void show(ViewportShots shots, Run run) {
        SimulationSession session = run.session();
        session.runFor(SECOND / 2);
        shots.show(session.fieldDescription(), run.robot, run.alliance, session.snapshot());
        shots.camera().setDriverHeading(OrbitCamera.driverHeadingFor(run.start.headingRadians()));
    }

    /** Looks at the robot from {@code yawOffset} degrees to the side of its heading, as a free camera that was chasing it. */
    private static void orbitRobot(ViewportShots shots, double yawOffsetDegrees, double pitchDegrees, double distance) {
        OrbitCamera camera = shots.camera();
        camera.select(OrbitCamera.Preset.CHASE);
        double[] robot = shots.followRobot();
        camera.set(Math.toDegrees(robot[2]) + yawOffsetDegrees, pitchDegrees, distance);
    }

    private static void shoot(ViewportShots shots, String name) throws IOException {
        System.out.println("SHOT " + shots.shoot(name));
    }

    private static final ViewportShots.Colour BLUE = (r, g, b) -> b > 2 * r && b > g + 20 && b > 60;
    private static final ViewportShots.Colour RED = (r, g, b) -> r > 2 * g && r > b + 20 && r > 60;
    private static final ViewportShots.Colour PURPLE_PIECE = (r, g, b) -> b > g + 50 && r > g + 30;
    private static final ViewportShots.Colour GREEN_PIECE = (r, g, b) -> g > r + 40 && g > b + 40;
    private static final ViewportShots.Colour YELLOW = (r, g, b) -> r > 150 && g > 120 && b < 60;
    private static final int W = ViewportShots.WIDTH;
    private static final int H = ViewportShots.HEIGHT;

    /** The views a driver is offered, and two from the corners; returns the top view, which has the whole field in it. */
    private static int[] takeTheViews(ViewportShots shots, String name) throws IOException {
        OrbitCamera camera = shots.camera();
        camera.select(OrbitCamera.Preset.TOP);
        shoot(shots, name + "-top");
        int[] top = shots.lastPicture();
        camera.select(OrbitCamera.Preset.DRIVER);
        shoot(shots, name + "-driver");
        camera.select(OrbitCamera.Preset.TOP);
        camera.set(40.0, 32.0, 5.8);
        shoot(shots, name + "-corner");
        camera.select(OrbitCamera.Preset.TOP);
        camera.set(140.0, 32.0, 5.8);
        shoot(shots, name + "-corner-far");
        camera.select(OrbitCamera.Preset.CHASE);
        shots.followRobot();
        shoot(shots, name + "-chase");
        orbitRobot(shots, 0.0, 22.0, 2.4);
        shoot(shots, name + "-front");
        return top;
    }

    /**
     * DECODE from the audience, which is where the goals' corners are in the top view whichever alliance starts: the
     * blue goal on the left and the red one on the right, and the pieces in their two colours between them.
     */
    private static void assertDecodeSides(int[] top) {
        int blueLeft = ViewportShots.count(top, 0, 0, W / 2, H, BLUE);
        int blueRight = ViewportShots.count(top, W / 2, 0, W, H, BLUE);
        int redLeft = ViewportShots.count(top, 0, 0, W / 2, H, RED);
        int redRight = ViewportShots.count(top, W / 2, 0, W, H, RED);
        assertTrue(blueLeft > 4 * blueRight, "the blue goal is on the left: blue " + blueLeft + " left, " + blueRight + " right");
        assertTrue(redRight > 4 * redLeft, "the red goal is on the right: red " + redLeft + " left, " + redRight + " right");
        assertTrue(ViewportShots.count(top, 0, 0, W, H, PURPLE_PIECE) > 100, "purple pieces");
        assertTrue(ViewportShots.count(top, 0, 0, W, H, GREEN_PIECE) > 100, "green pieces");
        assertTrue(ViewportShots.count(top, 0, 0, W, H, YELLOW) > 100, "the robot's yellow marker at its front");
    }

    @Test
    void decodeWithHornetAtTheBlueGoalSide() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new DecodeField(), Alliance.BLUE, "BLUE GOAL SIDE")) {
            show(shots, run);
            int[] top = takeTheViews(shots, "real-decode-blue");
            assertDecodeSides(top);
            assertTrue(
                    ViewportShots.count(top, 0, 0, W / 2, H, (r, g, b) -> b > 200 && g > 100 && r < 60) > 500,
                    "blue at the blue goal: its robot and its goal");
        }
    }

    @Test
    void decodeWithHornetAtTheRedGoalSide() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new DecodeField(), Alliance.RED, "RED GOAL SIDE")) {
            show(shots, run);
            int[] top = takeTheViews(shots, "real-decode-red");
            assertDecodeSides(top);
            assertTrue(
                    ViewportShots.count(top, W / 2, 0, W, H, (r, g, b) -> r > 240 && g < 100 && b < 100) > 500,
                    "red at the red goal: its robot and its goal");
        }
    }

    @Test
    void decodeObeliskAndGoalTagsFromTheField() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new DecodeField(), Alliance.BLUE, "BLUE AUDIENCE")) {
            show(shots, run);
            OrbitCamera camera = shots.camera();
            camera.select(OrbitCamera.Preset.CHASE);
            // Aim at the obelisk, which stands outside the middle of the goal-side wall, from inside the field.
            camera.track(-1.90, 0.0, 0.0, 30.0);
            camera.set(0.0, 24.0, 1.7);
            shoot(shots, "real-decode-obelisk");
            camera.select(OrbitCamera.Preset.CHASE);
            camera.track(-1.45, -1.40, 0.0, 30.0);
            camera.set(30.0, 22.0, 2.6);
            shoot(shots, "real-decode-blue-goal-tag");
            camera.select(OrbitCamera.Preset.CHASE);
            camera.track(-1.45, 1.40, 0.0, 30.0);
            camera.set(-30.0, 22.0, 2.6);
            shoot(shots, "real-decode-red-goal-tag");
            // The blue ramp runs from the goal along the wall, and its gate stands at the far end.
            camera.select(OrbitCamera.Preset.CHASE);
            camera.track(-0.7, -1.65, 0.0, 30.0);
            camera.set(60.0, 24.0, 2.4);
            shoot(shots, "real-decode-blue-ramp");
            camera.select(OrbitCamera.Preset.CHASE);
            camera.track(-0.7, -1.65, 0.0, 30.0);
            camera.set(-120.0, 30.0, 2.2);
            shoot(shots, "real-decode-blue-ramp-behind");
        }
    }

    @Test
    void decodeWithPiecesCarriedAndInFlight() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new DecodeField(), Alliance.BLUE, "BLUE GOAL SIDE")) {
            SimulationSession session = run.session();
            show(shots, run);
            orbitRobot(shots, 40.0, 25.0, 1.6);
            shoot(shots, "real-decode-carried");

            session.init("Hornet Intake Shooter");
            session.start();
            session.setGamepad(1, pad(true));
            double highest = 0;
            for (int step = 0; step < 160 && highest < 0.7; step++) {
                session.runFor(SECOND / 20);
                highest = highestPiece(session.snapshot());
            }
            System.out.printf(Locale.ROOT, "SHOOTING highest piece %.2f m at %.2f s%n", highest, session.now() / 1e9);
            shots.move(session.snapshot());
            orbitRobot(shots, 80.0, 14.0, 3.2);
            shoot(shots, "real-decode-in-flight");
            OrbitCamera camera = shots.camera();
            camera.select(OrbitCamera.Preset.DRIVER);
            shoot(shots, "real-decode-in-flight-driver");
            assertTrue(highest > 0.3, "a piece should have left the robot and be in the air: " + highest);
        }
    }

    /**
     * BIOBUZZ from behind the robot's own wall: its alliance's HIVE and tape are on the near side and the other
     * alliance's on the far side, and the yellow POLLEN lie along the walls.
     */
    private static void assertBiobuzzSides(int[] top, ViewportShots.Colour near, ViewportShots.Colour far) {
        int nearNear = ViewportShots.count(top, 0, H / 2, W, H, near);
        int nearFar = ViewportShots.count(top, 0, 0, W, H / 2, near);
        int farFar = ViewportShots.count(top, 0, 0, W, H / 2, far);
        int farNear = ViewportShots.count(top, 0, H / 2, W, H, far);
        assertTrue(nearNear > 3 * nearFar, "the robot's own alliance is on the near side: " + nearNear + " near, " + nearFar + " far");
        assertTrue(farFar > 3 * farNear, "the other alliance is on the far side: " + farFar + " far, " + farNear + " near");
        assertTrue(ViewportShots.count(top, 0, 0, W, H, YELLOW) > 80, "yellow pollen");
    }

    @Test
    void biobuzzWithHornetAtTheBlueWall() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new BiobuzzField(), Alliance.BLUE, "BLUE wall")) {
            System.out.println("BIOBUZZ start poses " + run.session().fieldDescription().startPoses());
            show(shots, run);
            assertBiobuzzSides(takeTheViews(shots, "real-biobuzz-blue"), BLUE, RED);
        }
    }

    @Test
    void biobuzzWithHornetAtTheRedWall() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new BiobuzzField(), Alliance.RED, "RED wall")) {
            show(shots, run);
            run.session().fieldDescription().tags().forEach(tag -> System.out.println(
                    "BIOBUZZ tag " + tag.id() + " host " + tag.hostEntityId() + " size " + tag.sizeMeters() + " at " + tag.local()));
            assertBiobuzzSides(takeTheViews(shots, "real-biobuzz-red"), RED, BLUE);
            // The tags of a HIVE ride on it, under the CELL that faces up: low and close, looking up at the red HIVE.
            OrbitCamera camera = shots.camera();
            camera.select(OrbitCamera.Preset.CHASE);
            camera.track(0.0, -0.3236, 0.0, 30.0);
            camera.set(0.0, 9.0, 1.7);
            shoot(shots, "real-biobuzz-hive-underside");
            camera.setTarget(0, -0.3236, 1.15);
            camera.set(45.0, 24.0, 2.0);
            shoot(shots, "real-biobuzz-hive-cells");
            camera.set(-135.0, 24.0, 2.0);
            shoot(shots, "real-biobuzz-hive-cells-other");
        }
    }

    /** The pivot of the red HIVE and how the HIVE frame maps to the field; see the field's HiveModel. */
    private static Vector3 hiveToField(double angle, double x, double y, double z) {
        double pivotY = -0.0254 * 12.74;
        double pivotZ = 0.0254 * 43.95;
        return new Vector3(x * Math.cos(angle) + z * Math.sin(angle), pivotY + y, pivotZ - x * Math.sin(angle) + z * Math.cos(angle));
    }

    private static double hiveDegrees(WorldSnapshot world, int entityId) {
        for (BodyState body : world.bodies()) {
            if (body.entityId() == entityId) {
                return Math.toDegrees(2 * Math.atan2(body.qy(), body.qw()));
            }
        }
        throw new AssertionError("no body " + entityId);
    }

    @Test
    void biobuzzAfterTheRedHiveTips() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new BiobuzzField(), Alliance.RED, "RED wall")) {
            SimulationSession session = run.session();
            show(shots, run);
            double before = hiveDegrees(session.snapshot(), BiobuzzIds.HIVE_RED);
            System.out.printf(Locale.ROOT, "HIVE before %.1f degrees%n", before);
            // Three POLLEN set down gently beside the three staged NECTAR in the audience CELL tip the red HIVE.
            double[] laterals = {2.38, 5.21, 8.07};
            double inch = 0.0254;
            for (int i = 0; i < laterals.length; i++) {
                Vector3 at = hiveToField(Math.toRadians(before), inch * 10.89, inch * laterals[i], inch * 0.09);
                session.world().spawnPiece(
                        5000 + i, BiobuzzIds.KIND_POLLEN, run.field.pieceSpecs().get(BiobuzzIds.KIND_POLLEN), at, Vector3.ZERO);
                session.runFor(SECOND / 2);
                System.out.printf(Locale.ROOT, "HIVE after pollen %d: %.1f degrees%n", i + 1, hiveDegrees(session.snapshot(), BiobuzzIds.HIVE_RED));
            }
            session.runFor(2 * SECOND);
            double after = hiveDegrees(session.snapshot(), BiobuzzIds.HIVE_RED);
            System.out.printf(Locale.ROOT, "HIVE after %.1f degrees%n", after);
            shots.move(session.snapshot());
            OrbitCamera camera = shots.camera();
            camera.select(OrbitCamera.Preset.TOP);
            camera.set(-60.0, 28.0, 4.2);
            shoot(shots, "real-biobuzz-hive-tipped");
            camera.select(OrbitCamera.Preset.TOP);
            camera.set(150.0, 30.0, 4.2);
            shoot(shots, "real-biobuzz-hive-tipped-other");
            assertTrue(Math.abs(after - before) > 30.0, "the HIVE should have tipped: " + before + " -> " + after);
        }
    }

    private static double highestPiece(WorldSnapshot world) {
        double highest = 0;
        for (BodyState body : world.bodies()) {
            if (body.kind() == PURPLE || body.kind() == GREEN) {
                highest = Math.max(highest, body.z());
            }
        }
        return highest;
    }

    private record Setting(String label, int width, int height, int samples) {}

    /**
     * What the real DECODE scene costs to draw at the sizes and multisampling the application uses: 1280x720 and
     * 1920x1080 at the full render scale, half of each (the 50 percent scale), with and without 4x MSAA. The numbers go
     * to the log and to {@code build/screens/frame-cost.txt}; only the counts are asserted, never a time.
     */
    @Test
    void theFrameCostOfTheRealDecodeScene() throws Exception {
        try (ViewportShots shots = openViewport(); Run run = open(new DecodeField(), Alliance.BLUE, "BLUE GOAL SIDE")) {
            SimulationSession session = run.session();
            show(shots, run);
            shots.camera().select(OrbitCamera.Preset.DRIVER);

            StringBuilder table = new StringBuilder();
            table.append(shots.glInfo()).append('\n');
            table.append("setting | submit p50/p99 ms | gpu p50/p99 ms | frame p50/p99 ms | heap B/frame | draw calls | instances | triangles\n");
            FieldRenderer.FrameStats stats = null;
            for (Setting setting : List.of(
                    new Setting("1280x720 4x MSAA", 1280, 720, 4),
                    new Setting("1280x720 no MSAA", 1280, 720, 0),
                    new Setting("640x360 4x MSAA (50 %)", 640, 360, 4),
                    new Setting("1920x1080 4x MSAA", 1920, 1080, 4),
                    new Setting("1920x1080 no MSAA", 1920, 1080, 0),
                    new Setting("960x540 4x MSAA (50 %)", 960, 540, 4),
                    new Setting("2560x1440 4x MSAA", 2560, 1440, 4))) {
                ViewportShots.Cost cost = shots.measure(setting.width, setting.height, setting.samples, 60, 600);
                stats = shots.lastFrame();
                table.append(String.format(
                        Locale.ROOT, "%s | %.3f / %.3f | %.3f / %.3f | %.3f / %.3f | %d | %d | %d | %d%n",
                        setting.label, cost.submitP50(), cost.submitP99(), cost.gpuP50(), cost.gpuP99(), cost.frameP50(),
                        cost.frameP99(), cost.allocatedBytesPerFrame(), stats.drawCalls(), stats.instances(), stats.triangles()));
            }
            table.append(String.format(
                    Locale.ROOT, "fixed instances %d, moving instances %d (the field describes %d scenery solids)%n",
                    stats.fixedInstances(), stats.movingInstances(), run.session().fieldDescription().scenery().size()));
            System.out.println(table);
            Files.createDirectories(ViewportShots.FOLDER);
            Files.writeString(ViewportShots.FOLDER.resolve("frame-cost.txt"), table.toString());

            assertTrue(stats.drawCalls() <= 20, "instancing keeps the draw calls to a handful: " + stats.drawCalls());
            assertTrue(stats.fixedInstances() > 100, "the field's scenery is a few hundred instances: " + stats.fixedInstances());
            assertTrue(stats.movingInstances() >= 30, "the pieces and the robot move: " + stats.movingInstances());
        }
    }
}
