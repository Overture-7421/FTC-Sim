package dev.ftcsim.hardware.device;

/**
 * The robot's turret as the devices on it see it: how far it has turned and where it turns about. A robot with no
 * turret has {@link #NONE}, which never turns.
 */
public interface TurretView {

    /** A robot with no turret, or a turret nothing turns. */
    TurretView NONE = new TurretView() {
        @Override
        public double yawRadians() {
            return 0.0;
        }

        @Override
        public double yawRateRadPerSec() {
            return 0.0;
        }
    };

    /** How far the turret has turned since the session began, counter-clockwise from facing forward, in radians. */
    double yawRadians();

    /** How fast it is turning, counter-clockwise, in radians per second. */
    double yawRateRadPerSec();

    /** Where it turns about, metres ahead of the chassis's centre. */
    default double axisX() {
        return 0.0;
    }

    /** And metres to the left of it. */
    default double axisY() {
        return 0.0;
    }
}
