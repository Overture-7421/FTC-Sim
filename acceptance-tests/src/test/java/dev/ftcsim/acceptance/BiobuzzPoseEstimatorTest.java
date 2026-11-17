package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.scenarios.opmodes.BiobuzzHive;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Pose;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Sighting;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * The BIOBUZZ stub's pose estimator on its own: odometry corrected by the camera's 3D tags. The sightings are made here from the
 * tags' places, the camera's mount and the turret's angle, by working the other way round from the estimator, so that a
 * mistake in one direction of the frame change shows up.
 */
class BiobuzzPoseEstimatorTest {
    private static final double BODY_CENTRE_HEIGHT = 0.1824;
    private static final double CAMERA_Z = 0.256;

    /** What the camera sees of a tag, as X right, Y down, Z ahead, from a robot at {@code truth} with the turret at {@code turret}. */
    private static Sighting see(int id, Pose truth, double turret) {
        BiobuzzHive.Tag tag = BiobuzzHive.TAGS.get(id);
        double c = Math.cos(-truth.heading());
        double s = Math.sin(-truth.heading());
        double rx = c * (tag.x() - truth.x()) - s * (tag.y() - truth.y());
        double ry = s * (tag.x() - truth.x()) + c * (tag.y() - truth.y());
        double rz = tag.z() - BODY_CENTRE_HEIGHT;
        // Undo the turret's turn about its axis.
        double dx = rx - BiobuzzPoseEstimator.TURRET_AXIS_X;
        double dy = ry - BiobuzzPoseEstimator.TURRET_AXIS_Y;
        double tc = Math.cos(-turret);
        double ts = Math.sin(-turret);
        double px = BiobuzzPoseEstimator.TURRET_AXIS_X + dx * tc - dy * ts;
        double py = BiobuzzPoseEstimator.TURRET_AXIS_Y + dx * ts + dy * tc;
        // From the camera, in the robot's axes; then undo the camera's upward tilt.
        double forward = px - BiobuzzPoseEstimator.CAMERA_X;
        double left = py - BiobuzzPoseEstimator.CAMERA_Y;
        double up = rz - CAMERA_Z;
        double pitch = BiobuzzPoseEstimator.CAMERA_PITCH_RADIANS;
        double along = forward * Math.cos(pitch) + up * Math.sin(pitch);
        double above = -forward * Math.sin(pitch) + up * Math.cos(pitch);
        return new Sighting(id, -left, -above, along);
    }

    private static List<Sighting> cluster(Pose truth, double turret, int... ids) {
        List<Sighting> sightings = new ArrayList<>();
        for (int id : ids) {
            sightings.add(see(id, truth, turret));
        }
        return sightings;
    }

    private static void assertPose(Pose expected, Pose actual, double metres, double radians, String message) {
        assertNotNull(actual, message);
        assertEquals(expected.x(), actual.x(), metres, message + " x");
        assertEquals(expected.y(), actual.y(), metres, message + " y");
        assertEquals(0.0, Math.atan2(Math.sin(expected.heading() - actual.heading()), Math.cos(expected.heading() - actual.heading())), radians,
                message + " heading");
    }

    @Test
    void twoTagsPlaceARobotThatDidNotKnowWhereItWasAndTheTurretAngleIsAccountedFor() {
        Pose truth = new Pose(-1.3, -0.8, 0.4);
        for (double turret : new double[] {0.0, 1.2, -2.5}) {
            BiobuzzPoseEstimator estimator = new BiobuzzPoseEstimator(null);
            estimator.update(0.0, 0.0, 0.0);
            assertNull(estimator.pose(), "no pose before the camera has seen the tags");

            assertTrue(estimator.correct(cluster(truth, turret, 31, 32), turret));

            assertPose(truth, estimator.pose(), 1e-9, 1e-9, "turret at " + turret);
        }
    }

    @Test
    void theEstimateFollowsTheOdometryBetweenFixesInTheFieldsFrame() {
        Pose truth = new Pose(-1.3, -0.8, 0.4);
        BiobuzzPoseEstimator estimator = new BiobuzzPoseEstimator(null);
        estimator.update(0.0, 0.0, 0.0);
        estimator.correct(cluster(truth, 0.0, 31, 32), 0.0);

        // The robot drives 0.5 m ahead and turns 0.3 rad, in the odometry's frame, which starts where the robot did.
        estimator.update(0.5, 0.0, 0.3);

        assertPose(new Pose(-1.3 + 0.5 * Math.cos(0.4), -0.8 + 0.5 * Math.sin(0.4), 0.7), estimator.pose(), 1e-9, 1e-9, "odometry only");
    }

    @Test
    void aFixPullsTheEstimateByTheCloseOrFarShareOfTheDifference() {
        Pose truth = new Pose(-1.3, -0.8, 0.4);
        Pose off = new Pose(-1.1, -0.9, 0.3);

        BiobuzzPoseEstimator near = new BiobuzzPoseEstimator(off);
        near.update(0.0, 0.0, 0.0);
        near.correct(cluster(truth, 0.0, 31, 32), 0.0);
        double share = BiobuzzPoseEstimator.CLOSE_POSITION_BLEND;
        assertPose(
                new Pose(off.x() + share * (truth.x() - off.x()), off.y() + share * (truth.y() - off.y()),
                        off.heading() + BiobuzzPoseEstimator.CLOSE_HEADING_BLEND * (truth.heading() - off.heading())),
                near.pose(), 1e-9, 1e-9, "close tags");

        // From the far side of the field the same tags are beyond three metres.
        Pose far = new Pose(3.5, -1.0, Math.PI);
        BiobuzzPoseEstimator distant = new BiobuzzPoseEstimator(new Pose(3.8, -1.1, Math.PI - 0.1));
        distant.update(0.0, 0.0, 0.0);
        distant.correct(cluster(far, 0.0, 30, 31, 32, 33), 0.0);
        Pose start = new Pose(3.8, -1.1, Math.PI - 0.1);
        assertPose(
                new Pose(start.x() + BiobuzzPoseEstimator.FAR_POSITION_BLEND * (far.x() - start.x()),
                        start.y() + BiobuzzPoseEstimator.FAR_POSITION_BLEND * (far.y() - start.y()),
                        start.heading() + BiobuzzPoseEstimator.FAR_HEADING_BLEND * (far.heading() - start.heading())),
                distant.pose(), 1e-9, 1e-9, "far tags");
    }

    @Test
    void oneTagCorrectsThePositionAndLeavesTheHeading() {
        Pose truth = new Pose(-1.3, -0.8, 0.4);
        BiobuzzPoseEstimator estimator = new BiobuzzPoseEstimator(new Pose(-1.3, -0.9, 0.4));
        estimator.update(0.0, 0.0, 0.0);

        BiobuzzPoseEstimator fresh = new BiobuzzPoseEstimator(null);
        fresh.update(0.0, 0.0, 0.0);
        assertFalse(fresh.correct(cluster(truth, 0.0, 31), 0.0), "alone, with no pose, one tag cannot place a robot");
        assertTrue(estimator.correct(cluster(truth, 0.0, 31), 0.0));

        assertEquals(0.4, estimator.pose().heading(), 1e-9);
        assertEquals(-0.9 + 0.6 * 0.1, estimator.pose().y(), 1e-9);
    }

    @Test
    void aFixThatDisagreesWithTheTagsOrWithTheEstimateIsIgnored() {
        Pose truth = new Pose(-1.3, -0.8, 0.4);
        BiobuzzPoseEstimator estimator = new BiobuzzPoseEstimator(truth);
        estimator.update(0.0, 0.0, 0.0);

        // One tag seen 0.5 m from where the others say it is, as when a HIVE has tipped.
        List<Sighting> torn = cluster(truth, 0.0, 31, 32, 33);
        Sighting moved = torn.get(2);
        torn.set(2, new Sighting(moved.id(), moved.x() + 0.5, moved.y(), moved.z()));
        assertFalse(estimator.correct(torn, 0.0), "the tags do not fit together");

        // A consistent set of tags that puts the robot two metres away.
        assertFalse(estimator.correct(cluster(new Pose(0.7, -0.8, 0.4), 0.0, 31, 32), 0.0), "too far from the estimate");
        assertFalse(estimator.correct(List.of(new Sighting(7, 0, 0, 1)), 0.0), "a tag the map does not have");
        assertPose(truth, estimator.pose(), 1e-9, 1e-9, "unchanged");
        assertEquals(0, estimator.fixes());
    }

    @Test
    void thePlainClassesCompileAsJava8BecauseARobotProjectDoesNotGetAnythingNewer() throws IOException {
        Path folder = Path.of("src", "scenarios", "java", "dev", "ftcsim", "scenarios", "opmodes");
        Path out = Files.createTempDirectory("java8-check");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "the tests run on a JDK");
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int status = compiler.run(
                    null, null, errors, "--release", "8", "-d", out.toString(),
                    folder.resolve("BiobuzzHive.java").toString(), folder.resolve("BiobuzzPoseEstimator.java").toString(),
                    folder.resolve("BiobuzzAutoShoot.java").toString());
            assertEquals(0, status, errors.toString());
        } finally {
            try (var files = Files.walk(out)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void theBlueCellToAimAtIsOnTheSideOfTheHiveNearerTheRobot() {
        BiobuzzHive.Tag audience = BiobuzzHive.nearestBlueCell(0.8);
        BiobuzzHive.Tag scoring = BiobuzzHive.nearestBlueCell(-0.8);

        assertEquals(0.410, audience.x(), 0.002, "the middle of the CELL with its side tipped down, 15.45 in along the arm");
        assertEquals(-0.410, scoring.x(), 0.002);
        assertEquals(0.3236, audience.y(), 0.001, "on the blue HIVE's pivot axis, 12.74 in from the centre line");
        assertEquals(1.0415, audience.z(), 0.002);
    }
}
