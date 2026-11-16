package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;

/** What the presentation and the sensors need to know about the DECODE field; it never changes from run to run. */
final class DecodeDescription {

    static final String LOAD_PURPLE = "load-purple";
    static final String LOAD_GREEN = "load-green";

    private static final int PURPLE_RGB = 0x8B3FD9;
    private static final int GREEN_RGB = 0x35C759;
    // The SDK's tag library gives the position of each goal tag in inches, and it is 6.5 in wide.
    private static final double INCH = 0.0254;
    private static final double GOAL_TAG_X = -58.3727 * INCH;
    private static final double GOAL_TAG_Y = 55.6425 * INCH;
    private static final double GOAL_TAG_Z = 29.5 * INCH;
    private static final int BLUE_GOAL_TAG = 20;
    private static final int RED_GOAL_TAG = 24;
    // A robot of the largest starting size, standing against a wall or a panel, has its centre this far away from it.
    private static final double ROBOT_CLEARANCE = 0.5 * 0.4572 + 0.012;

    private DecodeDescription() {}

    static FieldDescription build() {
        List<Scenery> scenery = new ArrayList<>();
        DecodeSolids.all().forEach(part -> scenery.add(part.scenery()));
        DecodeMarkings.all().forEach(part -> scenery.add(part.scenery()));
        return new FieldDescription(
                "decode",
                "DECODE 2025-26",
                scenery,
                bodyKinds(),
                tags(),
                startPoses(),
                List.of(
                        new HumanAction(LOAD_PURPLE, "Human player puts a purple artifact in the loading zone"),
                        new HumanAction(LOAD_GREEN, "Human player puts a green artifact in the loading zone")));
    }

    private static List<BodyKind> bodyKinds() {
        return List.of(
                artifact(Kinds.PURPLE, "purple artifact", PURPLE_RGB),
                artifact(Kinds.GREEN, "green artifact", GREEN_RGB),
                GateArm.bodyKind(false),
                GateArm.bodyKind(true),
                Obelisk.bodyKind());
    }

    private static BodyKind artifact(int kind, String name, int rgb) {
        FieldPart shape = new FieldPart(new Shape.Sphere(Dimensions.ARTIFACT_RADIUS), Pose3.IDENTITY, rgb);
        return new BodyKind(kind, name, List.of(shape));
    }

    private static List<FieldTag> tags() {
        List<FieldTag> tags = new ArrayList<>();
        Vector3 blue = new Vector3(GOAL_TAG_X, -GOAL_TAG_Y, GOAL_TAG_Z);
        Vector3 red = new Vector3(GOAL_TAG_X, GOAL_TAG_Y, GOAL_TAG_Z);
        tags.add(new FieldTag(
                BLUE_GOAL_TAG, Obelisk.TAG_SIZE_METERS, -1, Orientation.facing(Dimensions.FRONT_NORMAL_YAW).poseAt(blue)));
        tags.add(new FieldTag(
                RED_GOAL_TAG, Obelisk.TAG_SIZE_METERS, -1, Orientation.facing(-Dimensions.FRONT_NORMAL_YAW).poseAt(red)));
        tags.addAll(Obelisk.tags());
        return tags;
    }

    // Each alliance may start touching its own goal or on the audience side of its own half of the field.
    private static List<StartPose> startPoses() {
        Vector3 normal = Dimensions.frontNormal();
        double goalX = Dimensions.FRONT_PANEL_X + normal.x() * ROBOT_CLEARANCE;
        double goalY = Dimensions.FRONT_PANEL_Y + normal.y() * ROBOT_CLEARANCE;
        double audienceX = Dimensions.WALL - ROBOT_CLEARANCE;
        return List.of(
                new StartPose("BLUE GOAL SIDE", goalX, goalY, Dimensions.FRONT_NORMAL_YAW),
                new StartPose("BLUE AUDIENCE SIDE", audienceX, -0.30, Math.PI),
                new StartPose("RED GOAL SIDE", goalX, -goalY, -Dimensions.FRONT_NORMAL_YAW),
                new StartPose("RED AUDIENCE SIDE", audienceX, 0.30, Math.PI));
    }
}
