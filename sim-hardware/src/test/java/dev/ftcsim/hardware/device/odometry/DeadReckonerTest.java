package dev.ftcsim.hardware.device.odometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeadReckonerTest {

    @Test
    void movingForwardGoesAlongTheHeading() {
        DeadReckoner reckoner = new DeadReckoner();
        reckoner.setPose(0, 0, Math.PI / 2);

        reckoner.advance(100, 0, 0, 0, 0);

        assertEquals(0.0, reckoner.x(), 1e-9);
        assertEquals(100.0, reckoner.y(), 1e-9);
    }

    @Test
    void aQuarterCircleOfRadiusOneEndsOneAheadAndOneToTheLeft() {
        DeadReckoner reckoner = new DeadReckoner();

        // v = 1, omega = 1 for pi/2 seconds: the body-frame travel is pi/2 and the heading changes by pi/2.
        reckoner.advance(Math.PI / 2, 0, Math.PI / 2, 0, 0);

        assertEquals(1.0, reckoner.x(), 1e-12);
        assertEquals(1.0, reckoner.y(), 1e-12);
        assertEquals(Math.PI / 2, reckoner.heading(), 1e-12);
    }

    @Test
    void turningInPlaceLeavesThePoseWhereItWasWhenThePodOffsetsAreRight() {
        DeadReckoner reckoner = new DeadReckoner();
        // The forward pod is 100 mm right of the tracking point and the sideways pod 200 mm behind it. Turning
        // counter-clockwise by `turn` swings the forward pod 100 * turn forwards and the sideways pod 200 * turn right.
        double turn = 0.01;
        for (int i = 0; i < 628; i++) {
            reckoner.advance(100.0 * turn, -200.0 * turn, turn, -100.0, -200.0);
        }

        assertEquals(0.0, reckoner.x(), 1e-6);
        assertEquals(0.0, reckoner.y(), 1e-6);
    }

    @Test
    void aPodOffsetTheRobotCodeGotWrongMakesTheTrackingPointSwingOnACircle() {
        DeadReckoner reckoner = new DeadReckoner();
        // The same spin, but the chip was told the pods are on the tracking point. The pods' readings, rotated
        // with the heading and summed over half a turn, are (-2 * sideways, 2 * forward) in radians' worth of travel.
        double turn = Math.PI / 1000;
        for (int i = 0; i < 1000; i++) {
            reckoner.advance(100.0 * turn, -200.0 * turn, turn, 0, 0);
        }

        assertEquals(2 * 200.0, reckoner.x(), 0.01);
        assertEquals(2 * 100.0, reckoner.y(), 0.01);
    }
}
