package dev.ftcsim.game.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.physics.Vector3;
import org.junit.jupiter.api.Test;

class OrientationTest {

    private static void assertVector(Vector3 expected, Vector3 actual) {
        assertEquals(expected.x(), actual.x(), 1e-12);
        assertEquals(expected.y(), actual.y(), 1e-12);
        assertEquals(expected.z(), actual.z(), 1e-12);
    }

    @Test
    void anOrientationBuiltFromAxesCarriesTheAxesOntoThem() {
        Vector3[][] pairs = {
            {new Vector3(0.1687, -0.7585, 0.6294), new Vector3(0.1963, 0.6516, 0.7327)},
            {new Vector3(0, 1, 0), new Vector3(-1, 0, 0)},
            {new Vector3(-1, 0, 0), new Vector3(0, -1, 0)},
            {new Vector3(0, 0, 1), new Vector3(1, 0, 0)},
            {new Vector3(0, 0, -1), new Vector3(0, 1, 0)}};
        for (Vector3[] pair : pairs) {
            Vector3 x = pair[0].normalized();
            Vector3 y = pair[1].minus(x.times(pair[1].dot(x))).normalized();
            Orientation o = Orientation.fromAxes(x, y);
            assertVector(x, o.rotate(Vector3.X));
            assertVector(y, o.rotate(Vector3.Y));
            assertVector(x.cross(y), o.rotate(Vector3.Z));
        }
    }

    @Test
    void theMirrorImageOfARotationIsTheRotationOfTheMirrorImage() {
        Orientation o = Orientation.aboutAxis(new Vector3(0.3, -0.5, 0.8), 1.1);
        Vector3 v = new Vector3(0.7, -0.2, 0.4);
        Vector3 viaMirror = o.rotate(new Vector3(v.x(), -v.y(), v.z()));
        assertVector(new Vector3(viaMirror.x(), -viaMirror.y(), viaMirror.z()), o.mirrored().rotate(v));
    }

    @Test
    void aTagFacingAWayPointsOutOfItsFaceWithItsTopUp() {
        double yaw = 0.7;
        Orientation tag = Orientation.facing(yaw);
        assertVector(new Vector3(StrictMath.cos(yaw), StrictMath.sin(yaw), 0), tag.rotate(Vector3.Z));
        assertVector(Vector3.Z, tag.rotate(Vector3.Y));
        assertVector(new Vector3(-StrictMath.sin(yaw), StrictMath.cos(yaw), 0), tag.rotate(Vector3.X));
    }

    @Test
    void anAxisAndAngleBuildTheSameRotationAgain() {
        Orientation o = Orientation.fromAxes(new Vector3(0.1687, -0.7585, 0.6294), new Vector3(0.1963, 0.6516, 0.7327));
        Orientation again = Orientation.aboutAxis(o.axis(), o.angleRadians());
        Vector3 v = new Vector3(0.3, 0.4, -0.5);
        assertVector(o.rotate(v), again.rotate(v));
    }
}
