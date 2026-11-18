package dev.ftcsim.hardware.device;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import org.junit.jupiter.api.Test;

class TurretPlatformTest {
    private static TurretView turret(double yaw, double rate, double axisX, double axisY) {
        return new TurretView() {
            @Override
            public double yawRadians() {
                return yaw;
            }

            @Override
            public double yawRateRadPerSec() {
                return rate;
            }

            @Override
            public double axisX() {
                return axisX;
            }

            @Override
            public double axisY() {
                return axisY;
            }
        };
    }

    @Test
    void aTurretTurnedAQuarterTurnAboutAnAxisOffTheCentreCarriesWhatRidesOnItAroundTheAxis() {
        BodyPose frame = TurretPlatform.on(BodyPose.atOrigin(0), turret(Math.PI / 2, 0.0, 0.1, 0.0));

        Vec3 axis = frame.pointToField(new Vec3(0.1, 0.0, 0.0));
        Vec3 ahead = frame.pointToField(new Vec3(0.3, 0.0, 0.0));
        Vec3 heading = frame.directionToField(new Vec3(1.0, 0.0, 0.0));

        assertEquals(0.1, axis.x(), 1e-12, "the axis does not move");
        assertEquals(0.0, axis.y(), 1e-12);
        assertEquals(0.1, ahead.x(), 1e-12, "a point 0.2 m ahead of the axis is now 0.2 m to its left");
        assertEquals(0.2, ahead.y(), 1e-12);
        assertEquals(0.0, heading.x(), 1e-12, "and the turret's front points left");
        assertEquals(1.0, heading.y(), 1e-12);
    }

    @Test
    void theTurretsTurningAddsItsRateToTheChassisAndMovesPointsOffTheAxis() {
        BodyPose frame = TurretPlatform.on(BodyPose.atOrigin(0), turret(0.0, 2.0, 0.0, 0.0));

        assertEquals(2.0, frame.wz(), 1e-12);
        assertEquals(0.4, frame.velocityOfPoint(new Vec3(0.2, 0.0, 0.0)).y(), 1e-12, "0.2 m from the axis at 2 rad/s is 0.4 m/s sideways");
    }
}
