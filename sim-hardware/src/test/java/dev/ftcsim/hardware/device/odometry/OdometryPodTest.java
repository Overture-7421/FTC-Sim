package dev.ftcsim.hardware.device.odometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import org.junit.jupiter.api.Test;

class OdometryPodTest {

    private static final double TICKS_PER_METRE = 19_894.36789;
    private static final long SECOND = 1_000_000_000L;

    private static PodMount pod(double x, double y, double axisDegrees) {
        return new PodMount(x, y, Math.toRadians(axisDegrees), TICKS_PER_METRE, 1);
    }

    /** A pod on the scripted chassis, sampled {@code samples} times over {@code seconds}. */
    private static OdometryPod drive(PodMount mount, ScriptedBody body, double seconds, int samples) {
        OdometryPod pod = new OdometryPod(mount, body.at(0));
        for (int i = 1; i <= samples; i++) {
            pod.sample(body.at(Math.round(seconds * SECOND * i / samples)));
        }
        return pod;
    }

    @Test
    void aForwardPodCountsTheDistanceDrivenAndTheRateItIsDrivenAt() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(2, 0.5, 0, 0).build();

        OdometryPod pod = drive(pod(0.05, 0.1, 0), body, 2, 480);

        assertEquals(1.0, pod.travelMetres(), 1e-12);
        assertEquals(Math.round(TICKS_PER_METRE), pod.encoderCounts());
        assertEquals(0.5 * TICKS_PER_METRE, pod.encoderCountsPerSecond(), 1e-6);
    }

    @Test
    void aPodMountedTheOtherWayCountsDownAtItsOwnResolution() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(1, 1, 0, 0).build();
        PodMount reversed = new PodMount(0, 0, 0, 1000, -1);

        OdometryPod pod = drive(reversed, body, 1, 240);

        assertEquals(-1000, pod.encoderCounts());
        assertEquals(-1000.0, pod.encoderCountsPerSecond(), 1e-6);
    }

    @Test
    void sidewaysMotionIsInvisibleToAForwardPodAndCountedBySidewaysOne() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(1, 0, 0.5, 0).build();

        assertEquals(0.0, drive(pod(0, 0.1, 0), body, 1, 240).travelMetres(), 1e-12);
        assertEquals(0.5, drive(pod(0.2, 0, 90), body, 1, 240).travelMetres(), 1e-12);
    }

    @Test
    void aPodMountedAtAnAngleCountsTheComponentOfTheMotionAlongItsAxis() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(1, 1, 0, 0).build();

        assertEquals(0.5, drive(pod(0, 0, 60), body, 1, 240).travelMetres(), 1e-12);
    }

    @Test
    void spinningInPlaceMakesAPodOffTheCentreRollByTheArcItSwings() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(1, 0, 0, Math.PI / 2).build();

        // A point 0.1 m left of the centre moves backwards when the robot turns counter-clockwise; a point 0.2 m ahead moves left.
        assertEquals(-0.1 * Math.PI / 2, drive(pod(0, 0.1, 0), body, 1, 240).travelMetres(), 1e-9);
        assertEquals(0.2 * Math.PI / 2, drive(pod(0.2, 0, 90), body, 1, 240).travelMetres(), 1e-9);
    }

    @Test
    void anArcIsMeasuredExactlyEvenFromFewSamples() {
        // Radius 1 m for two radians: the pod 0.1 m left of the centre rides a circle of radius 0.9 m.
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(2, 1, 0, 1).build();

        OdometryPod pod = drive(pod(0, 0.1, 0), body, 2, 12);

        assertEquals(0.9 * 2, pod.travelMetres(), 1e-9);
    }

    @Test
    void countsAreWholeNumbersOfTheTravelAndANegativeTravelCountsDown() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(1, -0.123456, 0, 0).build();

        OdometryPod pod = drive(pod(0, 0, 0), body, 1, 240);

        assertEquals(Math.round(-0.123456 * TICKS_PER_METRE), pod.encoderCounts());
    }

    @Test
    void aPodMountedOnAHostFollowsTheChassisAfterEveryPhysicsStep() {
        ScriptedBody body = ScriptedBody.startingAt(0, 0, 0).move(2, 0.5, 0, 0).build();
        WorldRig world = new WorldRig(body, (origin, direction, maxMeters) -> null);
        OdometryPod pod = OdometryPod.mount(pod(0, 0, 0), world.host);

        world.runTo(SECOND);

        assertEquals(0.5, pod.travelMetres(), 1e-9);
    }
}
