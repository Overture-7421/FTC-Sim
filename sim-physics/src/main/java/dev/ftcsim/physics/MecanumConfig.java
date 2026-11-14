package dev.ftcsim.physics;

/**
 * Everything that describes a mecanum robot. The chassis frame has X forward, Y left, Z up and its
 * origin at the centre of the box collider; the wheels sit at the corners of the wheelbase/track
 * rectangle with their hubs one wheel radius above the floor when the robot stands level.
 *
 * @param groundClearanceM       gap between the underside of the box and the floor
 * @param comHeightM             height of the centre of mass above the floor when the robot stands level
 * @param wheelInertiaKgM2       spin inertia of a wheel alone; the motor adds its reflected rotor inertia
 * @param wheelDampingNmsPerRad  bearing and gearbox drag on each wheel
 * @param tireStiffnessNPerM     spring that carries the weight at each wheel
 * @param tireDampingNsPerM      damper in parallel with the tire spring
 * @param tractionFriction       friction coefficient along the roller axis, the direction in which a wheel grips
 * @param slipStiffnessNsPerM    grip force per unit of slip speed, until the friction limit is reached
 * @param rollerFriction         friction coefficient across the roller axis, the direction in which rollers roll freely
 * @param rollerViscousNsPerM    viscous drag across the roller axis
 * @param wheelFrictionNm        constant friction torque of the bearings and gearbox on each wheel, whatever its
 *                               speed; unlike the damping it brings a coasting robot to a halt in finite time
 * @param rollerRollingResistance constant drag across the roller axis, as a share of the load on the wheel; the
 *                               rollers' own bearings and tire, which a robot feels most when it strafes
 */
public record MecanumConfig(
        double massKg,
        double lengthM,
        double widthM,
        double heightM,
        double groundClearanceM,
        double comHeightM,
        double wheelRadiusM,
        double wheelbaseM,
        double trackM,
        DcMotor motor,
        double wheelInertiaKgM2,
        double wheelDampingNmsPerRad,
        double tireStiffnessNPerM,
        double tireDampingNsPerM,
        double tractionFriction,
        double slipStiffnessNsPerM,
        double rollerFriction,
        double rollerViscousNsPerM,
        double wheelFrictionNm,
        double rollerRollingResistance) {

    public MecanumConfig {
        if (!(massKg > 0 && lengthM > 0 && widthM > 0 && heightM > 0 && groundClearanceM >= 0 && comHeightM > 0
                && wheelRadiusM > 0 && wheelbaseM > 0 && trackM > 0 && wheelInertiaKgM2 >= 0
                && wheelDampingNmsPerRad >= 0 && tireStiffnessNPerM > 0 && tireDampingNsPerM >= 0
                && tractionFriction > 0 && slipStiffnessNsPerM > 0 && rollerFriction > 0 && rollerViscousNsPerM >= 0
                && wheelFrictionNm >= 0 && rollerRollingResistance >= 0)) {
            throw new IllegalArgumentException("chassis parameters must be positive");
        }
    }

    /** A robot without constant wheel friction or roller rolling resistance, as before they were modelled. */
    public MecanumConfig(
            double massKg,
            double lengthM,
            double widthM,
            double heightM,
            double groundClearanceM,
            double comHeightM,
            double wheelRadiusM,
            double wheelbaseM,
            double trackM,
            DcMotor motor,
            double wheelInertiaKgM2,
            double wheelDampingNmsPerRad,
            double tireStiffnessNPerM,
            double tireDampingNsPerM,
            double tractionFriction,
            double slipStiffnessNsPerM,
            double rollerFriction,
            double rollerViscousNsPerM) {
        this(massKg, lengthM, widthM, heightM, groundClearanceM, comHeightM, wheelRadiusM, wheelbaseM, trackM, motor,
                wheelInertiaKgM2, wheelDampingNmsPerRad, tireStiffnessNPerM, tireDampingNsPerM, tractionFriction,
                slipStiffnessNsPerM, rollerFriction, rollerViscousNsPerM, 0.0, 0.0);
    }

    /**
     * Generic 18 x 18 x 12 inch, 11.3 kg robot on four 96 mm mecanum wheels with 435 rpm motors. The
     * tire, grip and roller numbers are plausible estimates that have not been fitted to a real robot.
     */
    public static MecanumConfig generic18Inch() {
        return new MecanumConfig(
                11.3, 0.4572, 0.4572, 0.3048, 0.03, 0.08,
                0.048, 0.36, 0.36,
                DcMotor.goBilda435(), 2.3e-4, 5.0e-4,
                9_200, 300,
                0.8, 300,
                0.1, 0.25);
    }

    /** Height of the box centre above the floor when the robot stands level on its wheels. */
    public double restBoxCenterHeightM() {
        return 0.5 * heightM + groundClearanceM;
    }

    /** Where the centre of mass sits relative to the box centre, in the chassis frame. */
    Vector3 centerOfMassOffset() {
        return new Vector3(0, 0, comHeightM - restBoxCenterHeightM());
    }

    /** Wheel hub position relative to the box centre, in the chassis frame. */
    Vector3 hubPosition(WheelPosition wheel) {
        return new Vector3(
                0.5 * wheelbaseM * wheel.forwardSign(),
                0.5 * trackM * wheel.leftSign(),
                wheelRadiusM - restBoxCenterHeightM());
    }

    /** Spin inertia that a wheel's drive torque has to accelerate. */
    double wheelSpinInertiaKgM2() {
        return wheelInertiaKgM2 + motor.reflectedRotorInertiaKgM2();
    }
}
