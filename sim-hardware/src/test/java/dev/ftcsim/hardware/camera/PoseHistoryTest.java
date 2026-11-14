package dev.ftcsim.hardware.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.contracts.world.BodyPose;
import org.junit.jupiter.api.Test;

class PoseHistoryTest {

    private static BodyPose at(long nanos, double x, double heading) {
        return BodyPose.planar(nanos, x, 0, heading, 0, 0, 0);
    }

    @Test
    void betweenTwoSamplesThePoseIsInterpolatedAndOutsideThemTheNearestStandsIn() {
        PoseHistory history = new PoseHistory(8, 2);
        history.record(1000, new BodyPose[] {at(1000, 0.0, 0.0), at(1000, 5.0, 0.0)});
        history.record(2000, new BodyPose[] {at(2000, 1.0, 1.0), at(2000, 5.0, 0.0)});

        Rigid quarter = history.at(0, 1250);
        assertEquals(0.25, quarter.translation().x(), 1e-12);
        assertEquals(0.25, quarter.rotation().yaw(), 0.01, "the heading turns about a quarter of the way: the quaternions are blended and normalised");
        assertEquals(5.0, history.at(1, 1250).translation().x(), 0.0);
        assertEquals(0.0, history.at(0, 10).translation().x(), 0.0, "before the first sample");
        assertEquals(1.0, history.at(0, 99_999).translation().x(), 0.0, "after the last");
        assertEquals(1.0, history.at(0, 2000).translation().x(), 0.0, "exactly at a sample");
    }

    @Test
    void theRingKeepsTheNewestSamplesAndAnInterpolationWrapsAroundItsEnd() {
        PoseHistory history = new PoseHistory(4, 1);
        for (int i = 0; i < 10; i++) {
            history.record(1000L * i, new BodyPose[] {at(1000L * i, i, 0)});
        }

        assertEquals(9L * 1000, history.newestNanos());
        assertEquals(7.5, history.at(0, 7500).translation().x(), 1e-12);
        assertEquals(6.0, history.at(0, 100).translation().x(), 0.0, "what is older than the ring is its oldest sample");
    }

    @Test
    void aSecondSampleOfTheSameInstantReplacesTheFirst() {
        PoseHistory history = new PoseHistory(4, 1);
        history.record(1000, new BodyPose[] {at(1000, 1.0, 0)});
        history.record(1000, new BodyPose[] {at(1000, 2.0, 0)});
        history.record(2000, new BodyPose[] {at(2000, 4.0, 0)});

        assertEquals(3.0, history.at(0, 1500).translation().x(), 1e-12);
        assertEquals(4.0, history.at(0, 2000).translation().x(), 0.0);
    }
}
