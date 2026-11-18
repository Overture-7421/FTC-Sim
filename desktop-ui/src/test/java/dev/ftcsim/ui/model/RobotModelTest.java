package dev.ftcsim.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.RobotJoint;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RobotModelTest {
    private static final RobotModel MODEL = RobotModel.fromResource(RobotModel.BIOBUZZ);

    private static int indexOf(String name) {
        for (int i = 0; i < MODEL.groups().size(); i++) {
            if (MODEL.groups().get(i).name().equals(name)) {
                return i;
            }
        }
        throw new AssertionError("no group " + name);
    }

    private static float[] pose(double x, double y, double z, double yawRadians) {
        return new float[] {(float) x, (float) y, (float) z, 0f, 0f, (float) Math.sin(yawRadians / 2), (float) Math.cos(yawRadians / 2)};
    }

    /** Where the origin of a group's frame is, in the world. */
    private static double[] origin(float[] matrices, int group) {
        return new double[] {matrices[16 * group + 12], matrices[16 * group + 13], matrices[16 * group + 14]};
    }

    @Test
    void theBiobuzzModelIsInTheBuildWithEveryJointAGroupTurnsAboutAndParentsBeforeChildren() {
        assertNotNull(MODEL, "the build carries the model");
        assertTrue(MODEL.triangles() > 10_000, "the robot is there in full: " + MODEL.triangles());
        assertTrue(MODEL.triangles() <= 30_000, "and light: " + MODEL.triangles());
        assertTrue(MODEL.hasTranslucent(), "the lexan sheets are translucent");
        Set<String> seen = new HashSet<>();
        Set<RobotJoint> joints = new HashSet<>();
        for (RobotModel.Group group : MODEL.groups()) {
            assertTrue(group.parent() < MODEL.groups().indexOf(group), group.name() + " comes after its parent");
            assertTrue(seen.add(group.name()), "group names are unique: " + group.name());
            if (group.joint() != null) {
                joints.add(group.joint());
            }
            for (RobotModel.Chunk chunk : group.chunks()) {
                assertTrue(chunk.vertices() <= 65536 && chunk.vertices() > 0);
                assertEquals(chunk.vertices() * 6, chunk.positions().remaining());
                assertEquals(chunk.triangles() * 6, chunk.indices().remaining());
            }
        }
        for (RobotJoint joint : RobotJoint.values()) {
            assertTrue(joints.contains(joint), "a group turns with " + joint);
        }
        assertEquals(-1, MODEL.groups().get(0).parent());
        assertEquals("base", MODEL.groups().get(0).name());
        assertNull(RobotModel.fromResource("/no/such/model"), "a missing model is not an error: the window falls back to boxes");
    }

    @Test
    void theGroupsStandWhereTheChassisAndTheTurretPutThemAndTurnWithTheirJoints() {
        ModelPoser poser = new ModelPoser(MODEL);
        float[] joints = new float[RobotJoint.values().length];
        joints[RobotJoint.HOOD.ordinal()] = (float) Math.toRadians(1.9);
        float[] chassis = pose(2.0, 3.0, 0.18, Math.PI / 2);

        float[] resting = poser.pose(chassis, pose(2.0, 3.0 + 0.0, 0.18, Math.PI / 2), joints).clone();

        assertEquals(2.0, origin(resting, 0)[0], 1e-5, "the base is where the chassis is");
        assertEquals(3.0, origin(resting, 0)[1], 1e-5);
        int wheel = indexOf("wheel.fl");
        RobotModel.Group wheelGroup = MODEL.groups().get(wheel);
        assertEquals(2.0 - wheelGroup.pivot()[1], origin(resting, wheel)[0], 1e-4, "a pivot 0.18 m to the left lands to the left of a robot facing +Y");
        assertEquals(3.0 + wheelGroup.pivot()[0], origin(resting, wheel)[1], 1e-4);

        joints[RobotJoint.WHEEL_FRONT_LEFT.ordinal()] = (float) (Math.PI / 2);
        float[] turned = poser.pose(chassis, pose(2.0, 3.0, 0.18, Math.PI / 2), joints);
        // The wheel turns about its own axle, so its origin stays and its frame's X axis (a column) goes to the chassis's Z.
        assertEquals(origin(resting, wheel)[0], origin(turned, wheel)[0], 1e-4, "turning on its axle does not move the pivot");
        float[] x = {turned[16 * wheel], turned[16 * wheel + 1], turned[16 * wheel + 2]};
        assertEquals(1.0, Math.abs(x[0] * 0 + x[1] * 0 + x[2]), 1e-4, "a quarter turn about the lateral axis stands the forward axis on end");
    }

    @Test
    void theTurretCarriesWhatIsOnItRoundItsAxisAndAChildStaysWhereItsParentPutsIt() {
        ModelPoser poser = new ModelPoser(MODEL);
        float[] joints = new float[RobotJoint.values().length];
        float[] chassis = pose(0, 0, 0, 0);
        int turret = indexOf("turret");
        int hood = indexOf("hood");

        float[] ahead = poser.pose(chassis, pose(0.089, 0, 0, 0), joints).clone();
        float[] left = poser.pose(chassis, pose(0.089, 0, 0, Math.PI / 2), joints);

        double[] hoodAhead = origin(ahead, hood);
        double[] hoodLeft = origin(left, hood);
        double axisX = 0.089;
        // The hood is ahead of the turret's axis; a quarter turn to the left puts it to the left of the axis.
        assertEquals(axisX + (hoodAhead[0] - axisX), hoodAhead[0], 1e-6);
        assertEquals(axisX, hoodLeft[0], 0.02, "after a quarter turn the hood is beside the axis, not ahead of it");
        assertTrue(hoodLeft[1] > 0.05, "and on its left: " + hoodLeft[1]);
        assertEquals(origin(ahead, turret)[2], origin(left, turret)[2], 1e-6, "the turret turns, it does not rise");
    }
}
