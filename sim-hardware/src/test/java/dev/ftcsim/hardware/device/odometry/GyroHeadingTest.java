package dev.ftcsim.hardware.device.odometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GyroHeadingTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void theHeadingKeepsCountingWhenTheTrueYawWrapsAroundHalfATurn() {
        GyroHeading gyro = new GyroHeading(0, 1, 3.0, 0);

        gyro.sample(-3.1, SECOND / 240);
        gyro.sample(-2.9, 2 * SECOND / 240);

        // From 3.0 round the back to -3.1 is 0.1832 radians further, and from there to -2.9 another 0.2.
        assertEquals(2 * Math.PI - 6.1 + 0.2, gyro.heading(), 1e-12);
    }

    @Test
    void theScalarMultipliesWhatTheGyroMeasured() {
        GyroHeading gyro = new GyroHeading(0, 1.01, 0, 0);

        gyro.sample(1.0, SECOND);

        assertEquals(1.01, gyro.heading(), 1e-12);
    }

    @Test
    void aBiasMakesTheHeadingDriftAtThatRateEvenWhileTheChassisStandsStill() {
        GyroHeading gyro = new GyroHeading(0.01, 1, 0.5, 0);

        gyro.sample(0.5, 5 * SECOND);
        gyro.sample(0.5, 10 * SECOND);

        assertEquals(0.1, gyro.heading(), 1e-12);
    }

    @Test
    void writingTheHeadingMovesTheChipsIdeaOfWhereItFacesButNotTheGyro() {
        GyroHeading gyro = new GyroHeading(0, 1, 0, 0);
        gyro.sample(0.5, SECOND);

        gyro.setHeading(2.0);
        gyro.sample(0.75, 2 * SECOND);

        assertEquals(2.25, gyro.heading(), 1e-12);
    }
}
