package dev.ftcsim.contracts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BodyPoseTest {
    private static final double TOLERANCE = 1e-12;

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), TOLERANCE, "x of " + actual);
        assertEquals(expected.y(), actual.y(), TOLERANCE, "y of " + actual);
        assertEquals(expected.z(), actual.z(), TOLERANCE, "z of " + actual);
    }

    @Test
    void aBodyTurnedAQuarterTurnSendsItsForwardAxisToTheFieldsLeftAndBack() {
        BodyPose pose = BodyPose.planar(0, 1.0, 2.0, Math.PI / 2, 0, 0, 0);

        assertVec(new Vec3(0, 1, 0), pose.directionToField(new Vec3(1, 0, 0)));
        assertVec(new Vec3(1, 0, 0), pose.directionToBody(new Vec3(0, 1, 0)));
        assertVec(new Vec3(1.0, 3.0, 0), pose.pointToField(new Vec3(1, 0, 0)));
        assertEquals(Math.PI / 2, pose.yaw(), TOLERANCE);
    }

    @Test
    void aPointOnASpinningBodyMovesWithTheBodyPlusTheSpinTimesItsArm() {
        BodyPose pose = BodyPose.planar(0, 0, 0, Math.PI / 2, 0.5, 0, 2.0);

        // The point is 0.1 m ahead of the origin, which the quarter turn puts on the field's +Y axis; at 2 rad/s it
        // swings towards -X at 0.2 m/s.
        assertVec(new Vec3(0.5 - 0.2, 0, 0), pose.velocityOfPoint(new Vec3(0.1, 0, 0)));
    }

    @Test
    void pitchAndRollAreReadBackFromAGeneralRotation() {
        double pitch = 0.2;
        double roll = -0.1;
        // Z-Y-X order: yaw first, then pitch about the new Y, then roll about the new X.
        double cy = Math.cos(0.3 / 2), sy = Math.sin(0.3 / 2);
        double cp = Math.cos(pitch / 2), sp = Math.sin(pitch / 2);
        double cr = Math.cos(roll / 2), sr = Math.sin(roll / 2);
        BodyPose pose = new BodyPose(
                0, 0, 0, 0,
                cr * cp * cy + sr * sp * sy,
                sr * cp * cy - cr * sp * sy,
                cr * sp * cy + sr * cp * sy,
                cr * cp * sy - sr * sp * cy,
                0, 0, 0, 0, 0, 0);

        assertEquals(0.3, pose.yaw(), TOLERANCE);
        assertEquals(pitch, pose.pitch(), TOLERANCE);
        assertEquals(roll, pose.roll(), TOLERANCE);
    }

    @Test
    void goingToTheFieldAndBackIsTheIdentityForAnyBody() {
        BodyPose pose = new BodyPose(0, 0.4, -1.1, 0.2, 0.8, 0.1, -0.3, 0.5, 0, 0, 0, 0, 0, 0);
        double norm = Math.sqrt(0.8 * 0.8 + 0.1 * 0.1 + 0.3 * 0.3 + 0.5 * 0.5);
        pose = new BodyPose(0, 0.4, -1.1, 0.2, 0.8 / norm, 0.1 / norm, -0.3 / norm, 0.5 / norm, 0, 0, 0, 0, 0, 0);

        Vec3 body = new Vec3(0.3, -0.2, 0.7);

        assertVec(body, pose.directionToBody(pose.directionToField(body)));
        assertEquals(body.length(), pose.directionToField(body).length(), TOLERANCE);
    }
}
