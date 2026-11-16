package dev.ftcsim.physics;

import dev.ftcsim.contracts.hardware.MotorPlant;
import java.util.ArrayList;
import java.util.List;

/**
 * Handle to a mecanum robot in the world. It owns what the robot's hardware layer sees (wheel
 * commands, battery voltage, wheel angles and speeds) and, once per step, turns the ground under
 * each wheel into forces on the chassis body.
 *
 * <p>The model never moves the chassis itself. Each wheel probes the ground with a ray from its hub
 * along the chassis' down axis, derives a load from a tire spring and damper, and returns one force
 * at the contact point: the load along the surface normal plus the roller traction of
 * {@link MecanumWheel}. Weight transfer, pitch, roll and wheels leaving the ground therefore come
 * from the rigid-body engine. What counts as ground is up to the probe; {@link PhysicsWorld} offers
 * its static field geometry.
 */
public final class MecanumChassis {

    /** Pose and velocity of the chassis body when a step starts; the velocity is that of its centre of mass. */
    record Motion(Vector3 position, Quaternion rotation, Vector3 linearVelocity, Vector3 angularVelocity) {}

    /** Ground sampled by a probe ray: the surface point hit and the unit normal pointing away from the ground. */
    record GroundHit(Vector3 point, Vector3 normal) {}

    /** A force applied to the chassis at a world-space point. */
    record ContactForce(Vector3 point, Vector3 force) {}

    @FunctionalInterface
    interface GroundProbe {

        /** Closest ground surface along the ray, or null when nothing lies within {@code length}. */
        GroundHit cast(Vector3 origin, Vector3 direction, double length);
    }

    // The probe reaches this many wheel radii below the hub, enough to find a wheel that touches a slope.
    private static final double PROBE_REACH = 1.5;
    // A wheel grips a surface only if it faces the chassis' down axis within 60 degrees and the
    // surface normal is at least 30 degrees off the axle, i.e. the robot is not lying on its side.
    private static final double MIN_ALIGNMENT = 0.5;

    private final int entityId;
    private final MecanumConfig config;
    private final MecanumWheel[] wheels = new MecanumWheel[WheelPosition.values().length];
    private final double[] normalForces = new double[WheelPosition.values().length];
    private double batteryVolts;

    MecanumChassis(int entityId, MecanumConfig config) {
        this.entityId = entityId;
        this.config = config;
        this.batteryVolts = config.motor().nominalVolts();
        for (WheelPosition position : WheelPosition.values()) {
            wheels[position.ordinal()] = new MecanumWheel(config, position);
        }
    }

    public int entityId() {
        return entityId;
    }

    public MecanumConfig config() {
        return config;
    }

    /** Normalized motor command in [-1, 1]; positive rolls the robot forward. Takes effect on the next step. */
    public void setWheelCommand(WheelPosition wheel, double command) {
        wheels[wheel.ordinal()].setCommand(command);
    }

    public void setBatteryVolts(double volts) {
        if (!(volts >= 0.0)) {
            throw new IllegalArgumentException("battery voltage must not be negative: " + volts);
        }
        batteryVolts = volts;
    }

    public double wheelAngleRadians(WheelPosition wheel) {
        return wheels[wheel.ordinal()].angleRadians();
    }

    public double wheelSpeedRadPerSec(WheelPosition wheel) {
        return wheels[wheel.ordinal()].speedRadPerSec();
    }

    /**
     * One wheel's motor as a motor port of a hub sees it: the hub sets the volts, the wheel and the ground decide
     * what happens, and the encoder counts what the shaft did.
     *
     * @param polarity +1 when positive volts roll the robot forward, -1 when the motor is mounted the other way
     *                 round, as the motors on the left side of a symmetric drivetrain are; the encoder counts up
     *                 with positive volts either way
     */
    public MotorPlant wheelPlant(WheelPosition wheel, int polarity) {
        if (polarity != 1 && polarity != -1) {
            throw new IllegalArgumentException("polarity is +1 or -1: " + polarity);
        }
        return new WheelPlant(wheels[wheel.ordinal()], polarity);
    }

    private final class WheelPlant implements MotorPlant {
        private final MecanumWheel wheel;
        private final int polarity;

        WheelPlant(MecanumWheel wheel, int polarity) {
            this.wheel = wheel;
            this.polarity = polarity;
        }

        @Override
        public void applyVoltage(double volts) {
            wheel.setVolts(polarity * volts);
        }

        @Override
        public void floatTerminals() {
            wheel.floatTerminals();
        }

        @Override
        public long encoderCounts() {
            return (long) Math.floor(polarity * wheel.angleRadians() / (2.0 * Math.PI) * config.motor().encoderTicksPerRevolution());
        }

        @Override
        public double encoderCountsPerSecond() {
            return polarity * wheel.speedRadPerSec() / (2.0 * Math.PI) * config.motor().encoderTicksPerRevolution();
        }

        @Override
        public double currentAmps() {
            return wheel.currentAmps(batteryVolts);
        }
    }

    /** Encoder count of the wheel's motor, which is mounted straight on the wheel axle. */
    public long encoderTicks(WheelPosition wheel) {
        double revolutions = wheelAngleRadians(wheel) / (2.0 * Math.PI);
        return (long) Math.floor(revolutions * config.motor().encoderTicksPerRevolution());
    }

    /** Load the tire carried during the last step; zero while the wheel is off the ground. */
    public double wheelNormalForceN(WheelPosition wheel) {
        return normalForces[wheel.ordinal()];
    }

    /** Advances every wheel by one step and returns the forces to apply to the chassis body, in wheel order. */
    List<ContactForce> advance(Motion motion, GroundProbe probe, double dt) {
        Quaternion rotation = motion.rotation();
        Frame frame = new Frame(
                motion,
                rotation.rotate(Vector3.Y),
                rotation.rotate(Vector3.Z).times(-1.0),
                motion.position().plus(rotation.rotate(config.centerOfMassOffset())));
        List<ContactForce> forces = new ArrayList<>(wheels.length);
        for (WheelPosition position : WheelPosition.values()) {
            int index = position.ordinal();
            MecanumWheel.Contact contact = contactBelow(position, frame, probe);
            Vector3 traction = wheels[index].advance(contact, batteryVolts, dt);
            normalForces[index] = contact == null ? 0.0 : contact.normalForceN();
            if (contact != null) {
                forces.add(new ContactForce(contact.point(), traction.plus(contact.normal().times(contact.normalForceN()))));
            }
        }
        return forces;
    }

    /** What every wheel of one step shares: the body's motion, its axle and down directions, and its centre of mass. */
    private record Frame(Motion motion, Vector3 axle, Vector3 down, Vector3 centerOfMass) {}

    // Null when the wheel is off the ground, or on a surface it cannot grip.
    private MecanumWheel.Contact contactBelow(WheelPosition position, Frame frame, GroundProbe probe) {
        Motion motion = frame.motion();
        Vector3 hub = motion.position().plus(motion.rotation().rotate(config.hubPosition(position)));
        GroundHit hit = probe.cast(hub, frame.down(), PROBE_REACH * config.wheelRadiusM());
        if (hit == null) {
            return null;
        }
        Vector3 normal = hit.normal();
        double height = hub.minus(hit.point()).dot(normal);
        double compression = config.wheelRadiusM() - height;
        boolean grips = normal.dot(frame.down()) < -MIN_ALIGNMENT && frame.axle().cross(normal).length() > MIN_ALIGNMENT;
        if (compression <= 0.0 || !grips) {
            return null;
        }
        Vector3 point = hub.minus(normal.times(height));
        Vector3 velocity = motion.linearVelocity()
                .plus(motion.angularVelocity().cross(point.minus(frame.centerOfMass())));
        double load = config.tireStiffnessNPerM() * compression - config.tireDampingNsPerM() * velocity.dot(normal);
        return new MecanumWheel.Contact(point, normal, frame.axle(), velocity, Math.max(0.0, load));
    }
}
