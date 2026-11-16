package dev.ftcsim.physics;

/**
 * Spin state of one mecanum wheel and the solve that couples it to the ground.
 *
 * <p>A mecanum wheel carries free-spinning rollers whose axes lie at 45 degrees to the wheel plane,
 * so at the contact patch the floor can push the wheel only along the roller axis. Across the
 * roller axis the rollers roll with almost no resistance. The friction limits form an ellipse: the
 * roller-axis force is bounded by {@code tractionFriction * N}, the free-rolling force by the much
 * smaller {@code rollerFriction * N}, and together they stay inside the ellipse spanned by both.
 *
 * <p>The wheel speed follows from {@code J dw/dt = driveTorque - c w - reaction}, solved implicitly
 * for the speed at the end of the step. The motor is the Thevenin pair of {@link DcMotor}, and the
 * grip force is linear in slip up to its limit, so both pieces are a single linear solve. The
 * chassis velocity over the contact patch is held at its start-of-step value. Positive speed rolls
 * the robot forward.
 *
 * <p>Two losses that do not depend on speed are optional. The friction torque of the wheel's bearings and gearbox
 * is part of the same solve and exact: the wheel stays put until the net torque on it exceeds the friction, and
 * a coasting robot stops. The rolling resistance of the rollers acts across the roller axis outside the friction
 * ellipse, since it is not ground friction; it follows the slip speed viscously below a few centimetres per
 * second so that the explicit force cannot chatter.
 */
final class MecanumWheel {

    /**
     * The patch where a wheel touches the ground.
     *
     * @param point           where the wheel touches the ground
     * @param normal          unit surface normal pointing away from the ground
     * @param axle            unit vector along the wheel axle, pointing left
     * @param chassisVelocity velocity of the chassis point at the patch, relative to the ground
     * @param normalForceN    load the tire spring carries
     */
    record Contact(Vector3 point, Vector3 normal, Vector3 axle, Vector3 chassisVelocity, double normalForceN) {}

    private static final double INVERSE_SQRT_2 = Math.sqrt(0.5);
    // Slope at which the rollers' rolling resistance rises with slip speed until it reaches its constant value.
    private static final double ROLLING_DRAG_SLOPE_NS_PER_M = 100.0;

    private final MecanumConfig config;
    private final double rollerSign;
    private double angleRadians;
    private double speedRadPerSec;
    private double command;
    private Drive drive = Drive.COMMAND;
    private double volts;

    /** How the motor is being driven: by a normalized command, by volts on the winding, or not at all. */
    private enum Drive { COMMAND, VOLTS, FLOATING }

    MecanumWheel(MecanumConfig config, WheelPosition position) {
        this.config = config;
        this.rollerSign = position.rollerSign();
    }

    void setCommand(double command) {
        this.command = Math.max(-1.0, Math.min(1.0, command));
        drive = Drive.COMMAND;
    }

    /** Holds the winding at {@code volts}, as a hub's output stage does; positive rolls the robot forward. */
    void setVolts(double volts) {
        this.volts = volts;
        drive = Drive.VOLTS;
    }

    /** Opens the winding: no drive and no back-EMF braking, so only friction acts on the wheel. */
    void floatTerminals() {
        drive = Drive.FLOATING;
    }

    /**
     * Current the winding draws from the supply; zero while it floats. Under a normalized command the winding sees
     * that share of {@code batteryVolts}.
     */
    double currentAmps(double batteryVolts) {
        double windingVolts = switch (drive) {
            case COMMAND -> Math.max(-1.0, Math.min(1.0, command)) * batteryVolts;
            case VOLTS -> volts;
            case FLOATING -> 0.0;
        };
        return drive == Drive.FLOATING ? 0.0 : Math.abs(config.motor().currentAmpsAtVolts(windingVolts, speedRadPerSec));
    }

    double angleRadians() {
        return angleRadians;
    }

    double speedRadPerSec() {
        return speedRadPerSec;
    }

    /**
     * Advances the wheel by one step.
     *
     * @param contact the ground patch, or null when the wheel is off the ground
     * @return the traction force the ground exerts on the chassis, tangential to the ground; zero
     *         without contact
     */
    Vector3 advance(Contact contact, double batteryVolts, double dt) {
        DcMotor.Thevenin motor = switch (drive) {
            case COMMAND -> config.motor().thevenin(command, batteryVolts);
            case VOLTS -> config.motor().theveninAtVolts(volts);
            case FLOATING -> new DcMotor.Thevenin(0.0, 0.0);
        };
        double inertiaRate = config.wheelSpinInertiaKgM2() / dt;
        // The implicit step reads: stiffness * newSpeed = momentum - reaction torque of the ground.
        double stiffness = inertiaRate + motor.dampingNmsPerRad() + config.wheelDampingNmsPerRad();
        double momentum = inertiaRate * speedRadPerSec + motor.zeroSpeedTorqueNm();
        Vector3 traction = Vector3.ZERO;
        if (contact == null || contact.normalForceN() <= 0.0) {
            speedRadPerSec = afterFriction(momentum) / stiffness;
        } else {
            traction = rollOnGround(contact, momentum, stiffness);
        }
        angleRadians += speedRadPerSec * dt;
        return traction;
    }

    // Solves the implicit step for the wheel speed and returns the traction force it comes with.
    private Vector3 rollOnGround(Contact contact, double momentum, double stiffness) {
        Vector3 forward = contact.axle().cross(contact.normal()).normalized();
        Vector3 lateral = contact.normal().cross(forward);
        Vector3 roller = forward.plus(lateral.times(rollerSign)).times(INVERSE_SQRT_2);
        Vector3 free = lateral.minus(forward.times(rollerSign)).times(INVERSE_SQRT_2);
        double normal = contact.normalForceN();
        // A ground force F along a direction turns the wheel by arm * F, and the rim moves along that
        // direction at speed * arm.
        double rollerArm = config.wheelRadiusM() * INVERSE_SQRT_2;
        double freeArm = -rollerSign * rollerArm;

        // The free-rolling force follows the speed at the start of the step; it is small.
        double freeSlip = free.dot(contact.chassisVelocity()) - freeArm * speedRadPerSec;
        double freeLimit = config.rollerFriction() * normal;
        double freeForce = -clamp(config.rollerViscousNsPerM() * freeSlip, freeLimit);
        double ellipse = Math.sqrt(Math.max(0.0, 1.0 - square(freeForce / freeLimit)));
        double gripLimit = config.tractionFriction() * normal * ellipse;
        double rollingForce = -clamp(ROLLING_DRAG_SLOPE_NS_PER_M * freeSlip, config.rollerRollingResistance() * normal);
        double freeTotal = freeForce + rollingForce;

        double driven = momentum - freeArm * freeTotal;
        double gripRate = config.slipStiffnessNsPerM();
        double rollerGround = roller.dot(contact.chassisVelocity());
        double stickingSpeed = afterFriction(driven + rollerArm * gripRate * rollerGround)
                / (stiffness + rollerArm * rollerArm * gripRate);
        double stickingForce = gripRate * (rollerArm * stickingSpeed - rollerGround);
        double grip = clamp(stickingForce, gripLimit);
        speedRadPerSec = Math.abs(stickingForce) <= gripLimit
                ? stickingSpeed
                : afterFriction(driven - rollerArm * grip) / stiffness;
        return roller.times(grip).plus(free.times(freeTotal));
    }

    // What is left of a net torque on the spinning wheel once its constant friction has taken its share.
    private double afterFriction(double torque) {
        return Math.copySign(Math.max(Math.abs(torque) - config.wheelFrictionNm(), 0.0), torque);
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private static double square(double value) {
        return value * value;
    }
}
