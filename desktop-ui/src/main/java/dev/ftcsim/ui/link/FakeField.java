package dev.ftcsim.ui.link;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.RobotInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * The made-up field, robot and OpModes of {@link FakeSimulatorLink}: a 12 ft square with perimeter walls, two
 * goals, a low barrier, a turnstile that turns while an OpMode runs, and two kinds of piece. It exercises every
 * kind of description the viewport has to draw without belonging to any game.
 */
final class FakeField {
    static final double HALF = 1.8288;
    static final double WALL_HALF_THICKNESS = 0.0127;
    static final double WALL_HALF_HEIGHT = 0.15;
    static final double PIECE_RADIUS = 0.0635;
    static final double GOAL_RADIUS = 0.22;
    static final double GOAL_X = 1.45;
    static final double GOAL_Y = 1.45;
    static final double BARRIER_X = 0.8;
    static final double BARRIER_Y = -0.3;
    static final double BARRIER_HALF_X = 0.28;
    static final double BARRIER_HALF_Y = 0.16;
    static final double TURNSTILE_X = 0.0;
    static final double TURNSTILE_Y = 0.75;
    static final double START_X = 1.2;
    static final double START_Y = -1.4;
    static final double START_HEADING = Math.PI / 2;

    static final int KIND_GREEN = 100;
    static final int KIND_PURPLE = 101;
    static final int KIND_TURNSTILE = 102;
    static final int CHASSIS_ID = 1;
    static final int TURNSTILE_ID = 1000;
    static final int FIRST_PIECE_ID = 1001;

    static final String ACTION_FEED = "feed-piece";
    static final String ACTION_SCATTER = "scatter";

    static final String TELEOP = "Mecanum TeleOp";
    static final String SQUARE = "Drive Square";
    static final String TELEMETRY = "Telemetry Test";
    static final String CRASH = "Crash After 3 s";

    private static final int WALL_RGB = 0xC9D1D9;
    private static final int RED_RGB = 0xE53935;
    private static final int BLUE_RGB = 0x1E88E5;
    private static final int GREEN_RGB = 0x43A047;
    private static final int PURPLE_RGB = 0x8E24AA;

    private FakeField() {}

    /**
     * The fake field, standing in for the game that was asked for.
     *
     * @param id the id the request named, which the description reports as its own so that a UI can tell which game it
     *     is looking at
     */
    static FieldDescription description(String id) {
        List<Scenery> scenery = new ArrayList<>();
        double wall = HALF + WALL_HALF_THICKNESS;
        double wallLength = HALF + 2 * WALL_HALF_THICKNESS;
        scenery.add(box("wall north", wallLength, WALL_HALF_THICKNESS, WALL_HALF_HEIGHT, 0, wall, WALL_HALF_HEIGHT, WALL_RGB));
        scenery.add(box("wall south", wallLength, WALL_HALF_THICKNESS, WALL_HALF_HEIGHT, 0, -wall, WALL_HALF_HEIGHT, WALL_RGB));
        scenery.add(box("wall east", WALL_HALF_THICKNESS, HALF, WALL_HALF_HEIGHT, wall, 0, WALL_HALF_HEIGHT, WALL_RGB));
        scenery.add(box("wall west", WALL_HALF_THICKNESS, HALF, WALL_HALF_HEIGHT, -wall, 0, WALL_HALF_HEIGHT, WALL_RGB));
        scenery.add(goal("red goal", GOAL_X, GOAL_Y, RED_RGB));
        scenery.add(goal("blue goal", -GOAL_X, GOAL_Y, BLUE_RGB));
        scenery.add(post("red goal post", GOAL_X, GOAL_Y));
        scenery.add(post("blue goal post", -GOAL_X, GOAL_Y));
        scenery.add(box("barrier", BARRIER_HALF_X, BARRIER_HALF_Y, 0.08, BARRIER_X, BARRIER_Y, 0.08, 0x78909C));
        scenery.add(box("centre line", HALF, 0.015, 0.002, 0, 0, 0.002, 0xFAFAFA));

        List<BodyKind> kinds = List.of(
                new BodyKind(KIND_GREEN, "green piece", List.of(part(new Shape.Sphere(PIECE_RADIUS), Pose3.IDENTITY, GREEN_RGB))),
                new BodyKind(KIND_PURPLE, "purple piece", List.of(part(new Shape.Sphere(PIECE_RADIUS), Pose3.IDENTITY, PURPLE_RGB))),
                new BodyKind(KIND_TURNSTILE, "turnstile", List.of(
                        part(new Shape.Cylinder(0.05, 0.2), Pose3.at(0, 0, 0.2), 0x90A4AE),
                        part(new Shape.Box(0.35, 0.03, 0.03), Pose3.at(0, 0, 0.38), 0xFFB300),
                        part(new Shape.Box(0.03, 0.35, 0.03), Pose3.at(0, 0, 0.38), 0xFFB300))));

        return new FieldDescription(
                id,
                "Fake field (stands in for " + id + ")",
                scenery,
                kinds,
                List.of(
                        new FieldTag(20, 0.1651, -1, Pose3.turned(GOAL_X, GOAL_Y, 0.5, 0, 0, 1, Math.PI)),
                        new FieldTag(24, 0.1651, -1, Pose3.turned(-GOAL_X, GOAL_Y, 0.5, 0, 0, 1, Math.PI))),
                startPoses(),
                humanActions());
    }

    /**
     * Where the fake field lets a robot start. The names say which alliance a place belongs to, as the real games'
     * do, and one belongs to neither.
     */
    static List<StartPose> startPoses() {
        return List.of(
                new StartPose("RED start", START_X, START_Y, START_HEADING),
                new StartPose("RED far side", 0.4, START_Y, START_HEADING),
                new StartPose("BLUE start", -START_X, START_Y, START_HEADING),
                new StartPose("BLUE far side", -0.4, START_Y, START_HEADING),
                new StartPose("Centre of the field", 0.0, 0.0, 0.0));
    }

    static List<HumanAction> humanActions() {
        return List.of(new HumanAction(ACTION_FEED, "Feed a piece"), new HumanAction(ACTION_SCATTER, "Scatter pieces"));
    }

    /** What the fake simulator offers before a session is open: a bare floor and two fields of the fake kind. */
    static Catalogue catalogue() {
        return new Catalogue(
                List.of(
                        new FieldChoice("none", "Bare floor", List.of(), List.of()),
                        new FieldChoice("decode", "DECODE", startPoses(), humanActions()),
                        new FieldChoice("biobuzz", "BIOBUZZ", startPoses(), humanActions())),
                List.of("hornet-2026", "practice-bot"));
    }

    static RobotInfo robot() {
        return new RobotInfo(
                "Fake robot", 0.43, 0.40, 0.28,
                List.of("frontLeft", "backLeft", "frontRight", "backRight"),
                List.of("claw"),
                List.of("imu"));
    }

    static List<OpModeDescriptor> opModes() {
        return List.of(
                new OpModeDescriptor(TELEOP, "Fake", OpModeKind.TELEOP, "dev.ftcsim.fake.MecanumTeleOp"),
                new OpModeDescriptor(SQUARE, "Fake", OpModeKind.AUTONOMOUS, "dev.ftcsim.fake.DriveSquare"),
                new OpModeDescriptor(TELEMETRY, "Fake", OpModeKind.TELEOP, "dev.ftcsim.fake.TelemetryTest"),
                new OpModeDescriptor(CRASH, "Fake", OpModeKind.TELEOP, "dev.ftcsim.fake.CrashAfterThreeSeconds"));
    }

    private static FieldPart part(Shape shape, Pose3 local, int rgb) {
        return new FieldPart(shape, local, rgb);
    }

    private static Scenery box(
            String name, double halfX, double halfY, double halfZ, double x, double y, double z, int rgb) {
        return new Scenery(name, part(new Shape.Box(halfX, halfY, halfZ), Pose3.IDENTITY, rgb), Pose3.at(x, y, z));
    }

    private static Scenery goal(String name, double x, double y, int rgb) {
        return new Scenery(name, part(new Shape.Cylinder(GOAL_RADIUS, 0.01), Pose3.IDENTITY, rgb), Pose3.at(x, y, 0.01));
    }

    private static Scenery post(String name, double x, double y) {
        return new Scenery(name, part(new Shape.Cylinder(0.04, 0.25), Pose3.IDENTITY, 0xB0BEC5), Pose3.at(x, y, 0.25));
    }
}
