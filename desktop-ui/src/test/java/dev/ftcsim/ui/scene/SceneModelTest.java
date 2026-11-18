package dev.ftcsim.ui.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneModelTest {
    private static FieldDescription field(List<Scenery> scenery, List<BodyKind> kinds) {
        return new FieldDescription("test", "Test", scenery, kinds, List.of(), List.of(), List.of());
    }

    private static RobotInfo robot() {
        return new RobotInfo("robot", 0.4, 0.3, 0.2, List.of(), List.of(), List.of());
    }

    @Test
    void sceneryIsPlacedInFieldCoordinatesWithItsPartTurnedAndMovedByThePose() {
        // A wall turned a quarter turn about Z and moved to (1, 2): its local +X offset of 0.5 ends up on +Y.
        FieldPart part = new FieldPart(new Shape.Box(0.5, 0.1, 0.2), Pose3.at(0.5, 0, 0), 0xFF8000);
        Pose3 pose = Pose3.turned(1, 2, 0, 0, 0, 1, Math.PI / 2);

        SceneModel scene = SceneModel.of(field(List.of(new Scenery("wall", part, pose)), List.of()), null);

        SceneModel.Part wall = scene.scenery().get(0);
        assertEquals(SceneModel.Mesh.BOX, wall.mesh());
        assertEquals(1.0f, wall.x(), 1e-5f);
        assertEquals(2.5f, wall.y(), 1e-5f);
        assertEquals((float) Math.sin(Math.PI / 4), wall.qz(), 1e-5f);
        assertEquals(0.5f, wall.halfX(), 1e-6f);
        assertEquals(1f, wall.red(), 1e-6f);
        assertEquals(0x80 / 255f, wall.green(), 1e-6f);
        assertEquals(0f, wall.blue(), 1e-6f);
    }

    @Test
    void spheresGetMoreDetailTheLargerTheyAre() {
        FieldPart tiny = new FieldPart(new Shape.Sphere(0.02), Pose3.IDENTITY, 0);
        FieldPart ball = new FieldPart(new Shape.Sphere(0.0635), Pose3.IDENTITY, 0);
        FieldPart dome = new FieldPart(new Shape.Sphere(0.3), Pose3.IDENTITY, 0);

        SceneModel scene = SceneModel.of(
                field(List.of(), List.of(new BodyKind(5, "a", List.of(tiny)), new BodyKind(6, "b", List.of(ball)), new BodyKind(7, "c", List.of(dome)))),
                null);

        assertEquals(SceneModel.Mesh.SPHERE_COARSE, scene.parts(5)[0].mesh());
        assertEquals(SceneModel.Mesh.SPHERE_MEDIUM, scene.parts(6)[0].mesh());
        assertEquals(SceneModel.Mesh.SPHERE_FINE, scene.parts(7)[0].mesh());
    }

    @Test
    void cylindersKeepTheirAxisOnLocalZ() {
        FieldPart post = new FieldPart(new Shape.Cylinder(0.05, 0.25), Pose3.IDENTITY, 0);

        SceneModel scene = SceneModel.of(field(List.of(new Scenery("post", post, Pose3.at(0, 0, 0.25))), List.of()), null);

        SceneModel.Part drawn = scene.scenery().get(0);
        assertEquals(SceneModel.Mesh.CYLINDER, drawn.mesh());
        assertEquals(0.05f, drawn.halfX(), 1e-6f);
        assertEquals(0.05f, drawn.halfY(), 1e-6f);
        assertEquals(0.25f, drawn.halfZ(), 1e-6f);
    }

    @Test
    void aBodyKindUnknownToTheFieldHasNothingToDraw() {
        SceneModel scene = SceneModel.of(field(List.of(), List.of()), robot());

        assertEquals(0, scene.parts(123).length);
        assertTrue(scene.parts(BodyKinds.CHASSIS).length > 0);
    }

    @Test
    void theRobotIsABoxFromItsInfoCentredOnItsPosition() {
        SceneModel scene = SceneModel.of(null, robot());

        SceneModel.Part chassis = scene.parts(BodyKinds.CHASSIS)[0];
        assertEquals(SceneModel.Mesh.BOX, chassis.mesh());
        assertEquals(0.2f, chassis.halfX(), 1e-6f);
        assertEquals(0.15f, chassis.halfY(), 1e-6f);
        assertEquals(0.1f, chassis.halfZ(), 1e-6f);
        assertEquals(0f, chassis.x());
    }

    @Test
    void theBiobuzzRobotIsItsCadAndNotBoxesAndOtherRobotsAreBoxes() {
        RobotInfo biobuzz = new RobotInfo("robot", 0.4, 0.3, 0.2, List.of(), List.of(), List.of(), RobotInfo.BIOBUZZ_MODEL);

        SceneModel scene = SceneModel.of(null, biobuzz);

        assertTrue(scene.robotModel() != null, "the build carries the model");
        assertEquals(0, scene.parts(BodyKinds.CHASSIS).length, "the CAD stands in for the box");
        assertEquals(0, scene.parts(BodyKinds.TURRET).length);
        SceneModel plain = SceneModel.of(null, robot());
        assertEquals(null, plain.robotModel());
        assertTrue(plain.parts(BodyKinds.CHASSIS).length > 0);
    }

    @Test
    void aPartIsPlacedOnItsBodyByRotatingItsOffsetAndMultiplyingTheRotations() {
        FieldPart arm = new FieldPart(new Shape.Box(0.1, 0.1, 0.1), Pose3.at(1, 0, 0), 0);
        SceneModel scene = SceneModel.of(field(List.of(), List.of(new BodyKind(9, "arm", List.of(arm)))), null);
        float half = (float) Math.sin(Math.PI / 4);
        float[] body = {10f, 20f, 0f, 0f, 0f, half, (float) Math.cos(Math.PI / 4)};
        float[] placed = new float[7];

        scene.parts(9)[0].placeOn(body, 0, placed);

        assertEquals(10f, placed[0], 1e-5f);
        assertEquals(21f, placed[1], 1e-5f, "a quarter turn sends the +X offset onto +Y");
        assertEquals(half, placed[5], 1e-5f);
    }

    @Test
    void theFloorIsAFieldWideWhenThereIsAFieldAndGrowsToTheSceneryAndABareFloorIsLarger() {
        FieldPart far = new FieldPart(new Shape.Box(0.1, 0.1, 0.1), Pose3.IDENTITY, 0);

        assertEquals(1.8288f, SceneModel.of(field(List.of(), List.of()), null).floorHalfExtent(), 1e-6f);
        assertEquals(6.0f, SceneModel.of(null, null).floorHalfExtent(), 1e-6f);
        SceneModel wide = SceneModel.of(field(List.of(new Scenery("far", far, Pose3.at(4, 0, 0))), List.of()), null);
        assertEquals(4.1f, wide.floorHalfExtent(), 1e-5f);
    }

    @Test
    void theRobotsOwnPartsReplaceWhatTheFieldSaysForItsKind() {
        FieldDescription description = field(List.of(), List.of(new BodyKind(BodyKinds.CHASSIS, "chassis", List.of())));

        SceneModel scene = SceneModel.of(description, robot());

        assertEquals(2, scene.parts(BodyKinds.CHASSIS).length, "the chassis box and the stripe that shows which way it faces");
    }

    @Test
    void theRobotIsDrawnInTheColourOfTheAllianceItPlaysFor() {
        SceneModel red = SceneModel.of(null, robot(), SceneModel.robotRgb(Alliance.RED));
        SceneModel blue = SceneModel.of(null, robot(), SceneModel.robotRgb(Alliance.BLUE));
        SceneModel none = SceneModel.of(null, robot());

        assertTrue(red.parts(BodyKinds.CHASSIS)[0].red() > red.parts(BodyKinds.CHASSIS)[0].blue() + 0.4f, "red alliance, red body");
        assertTrue(blue.parts(BodyKinds.CHASSIS)[0].blue() > blue.parts(BodyKinds.CHASSIS)[0].red() + 0.4f, "blue alliance, blue body");
        assertEquals(blue.parts(BodyKinds.CHASSIS)[0], none.parts(BodyKinds.CHASSIS)[0], "without an alliance the robot is blue");
        assertEquals(0xFFC107 >> 16 & 0xFF, Math.round(red.parts(BodyKinds.CHASSIS)[1].red() * 255f), "the front stripe keeps its colour");
    }

    @Test
    void aBoxAsWideAsTheFieldWhoseTopIsInTheFloorPlaneIsTheFloorAndNothingElseIs() {
        FieldPart slab = new FieldPart(new Shape.Box(2.0, 2.0, 0.25), Pose3.IDENTITY, 0x6B6F73);
        FieldPart tape = new FieldPart(new Shape.Box(1.5, 0.0127, 0.0003), Pose3.IDENTITY, 0xFFFFFF);
        FieldPart wall = new FieldPart(new Shape.Box(2.0, 0.02, 0.14), Pose3.IDENTITY, 0xBFD6E4);
        FieldPart raisedDeck = new FieldPart(new Shape.Box(2.0, 2.0, 0.05), Pose3.IDENTITY, 0x808080);

        SceneModel scene = SceneModel.of(
                field(
                        List.of(
                                new Scenery("floor", slab, Pose3.at(0, 0, -0.25)),
                                new Scenery("tape", tape, Pose3.turned(0, 0, 0.0003, 0, 0, 1, Math.PI / 4)),
                                new Scenery("wall", wall, Pose3.at(0, 1.8, 0.14)),
                                new Scenery("deck", raisedDeck, Pose3.at(0, 0, 0.05))),
                        List.of()),
                null);

        assertTrue(scene.scenery().get(0).floor(), "a slab that is flush with the floor plane");
        assertFalse(scene.scenery().get(1).floor(), "a long tape line, even turned across the diagonal, is thin");
        assertFalse(scene.scenery().get(2).floor(), "a wall stands on the floor");
        assertFalse(scene.scenery().get(3).floor(), "a deck above the floor is something to stand on, not the floor");
    }

    @Test
    void theRendererAddsNoFloorUnderOneThatCoversTheWholeSceneButDoesUnderOneThatDoesNot() {
        FieldPart slab = new FieldPart(new Shape.Box(2.0, 2.0, 0.25), Pose3.IDENTITY, 0x6B6F73);
        FieldPart small = new FieldPart(new Shape.Box(1.0, 1.0, 0.25), Pose3.IDENTITY, 0x6B6F73);
        FieldPart wall = new FieldPart(new Shape.Box(0.02, 2.4, 0.14), Pose3.IDENTITY, 0xBFD6E4);
        Scenery floor = new Scenery("floor", slab, Pose3.at(0, 0, -0.25));

        assertTrue(SceneModel.of(field(List.of(floor), List.of()), null).bringsItsOwnFloor());
        assertFalse(SceneModel.of(field(List.of(), List.of()), null).bringsItsOwnFloor(), "no floor at all");
        assertFalse(
                SceneModel.of(field(List.of(new Scenery("floor", small, Pose3.at(0, 0, -0.25))), List.of()), null).bringsItsOwnFloor(),
                "a floor that is not as wide as the field's walls and the rest of the scene is not enough");
        assertFalse(
                SceneModel.of(field(List.of(floor, new Scenery("wall", wall, Pose3.at(2.4, 0, 0.14))), List.of()), null).bringsItsOwnFloor(),
                "a wall beyond the floor's edge makes the scene wider than the floor");
    }

    @Test
    void aTagFixedToTheFieldIsThreeLayersOfSquareOnTheSceneryFacingAlongItsOwnZ() {
        // A 0.2 m tag on a wall at x = -1, its face looking along +X: its local Z is turned onto world +X.
        Pose3 facingPlusX = new Pose3(-1.0, 0.5, 0.7, Math.sqrt(0.5), 0.0, Math.sqrt(0.5), 0.0);
        FieldDescription withTag = new FieldDescription(
                "t", "T", List.of(), List.of(), List.of(new FieldTag(20, 0.2, -1, facingPlusX)), List.of(), List.of());

        SceneModel scene = SceneModel.of(withTag, null);

        assertEquals(3, scene.scenery().size(), "a black square, a white one on it and a black core");
        SceneModel.Part outer = scene.scenery().get(0);
        SceneModel.Part white = scene.scenery().get(1);
        SceneModel.Part core = scene.scenery().get(2);
        assertTrue(outer.x() > -1.0f && outer.x() < -0.99f, "the layers stand off the surface the tag is on, towards its face: " + outer.x());
        assertTrue(white.x() > outer.x() && core.x() > white.x(), "the white layer is in front of the black and the core in front of both");
        assertEquals(0.5f, outer.y(), 1e-5f);
        assertEquals(0.7f, outer.z(), 1e-5f);
        assertTrue(outer.red() < 0.1f && white.red() > 0.9f && core.red() < 0.1f);
        assertEquals(0.1f, outer.halfY(), 1e-6f, "the tag is 0.2 m across");
        assertTrue(white.halfY() < outer.halfY() && core.halfY() < white.halfY());
    }

    @Test
    void aTagOnABodyRidesOnThatBodyAndNotOnItsKind() {
        FieldDescription withTag = new FieldDescription(
                "t", "T", List.of(), List.of(new BodyKind(5, "prism", List.of())),
                List.of(new FieldTag(21, 0.2, 1200, Pose3.at(0.08, 0.0, 0.14))), List.of(), List.of());

        SceneModel scene = SceneModel.of(withTag, null);

        assertEquals(0, scene.scenery().size(), "it is not scenery");
        assertEquals(0, scene.parts(5).length);
        assertEquals(3, scene.carriedBy(1200).length);
        assertEquals(0, scene.carriedBy(1201).length);
        assertEquals(0.08f, scene.carriedBy(1200)[0].x(), 5e-3f, "its pose is relative to the body");
    }
}
