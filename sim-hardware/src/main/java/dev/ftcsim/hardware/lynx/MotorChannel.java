package dev.ftcsim.hardware.lynx;

import dev.ftcsim.contracts.hardware.MotorPlant;

/**
 * One motor port of a hub: the registers the SDK configures, the control law that turns them into a duty cycle on
 * every firmware tick, and the output stage that applies it to the connected plant.
 */
final class MotorChannel {

    /** The power command's full scale; the control loops also work in these units. */
    static final double FULL_POWER = 32767.0;

    /** Matches the SDK's default target position tolerance. */
    private static final int DEFAULT_TOLERANCE = 5;

    /** An estimate: REV rates a motor port for 10 A. */
    private static final int DEFAULT_CURRENT_ALERT_MILLIAMPS = 10_000;

    private final int index;
    private final Port<MotorPlant> port;
    private final Diagnostics diagnostics;
    private final PidLoop velocityLoop = new PidLoop(PidfCoefficients.DEFAULT_VELOCITY);
    private final PidLoop positionLoop = new PidLoop(PidfCoefficients.DEFAULT_POSITION);

    private RunMode mode = RunMode.OPEN_LOOP;
    private boolean floatAtZero;
    private boolean enabled;
    private int constantPower;
    private int targetVelocity;
    private int targetPosition;
    private boolean targetPositionSet;
    private int tolerance = DEFAULT_TOLERANCE;
    private int currentAlertMilliamps = DEFAULT_CURRENT_ALERT_MILLIAMPS;
    private long encoderOrigin;

    MotorChannel(int index, Port<MotorPlant> port, Diagnostics diagnostics) {
        this.index = index;
        this.port = port;
        this.diagnostics = diagnostics;
    }

    Port<MotorPlant> port() {
        return port;
    }

    RunMode mode() {
        return mode;
    }

    boolean floatAtZero() {
        return floatAtZero;
    }

    boolean enabled() {
        return enabled;
    }

    int constantPower() {
        return constantPower;
    }

    int targetVelocity() {
        return targetVelocity;
    }

    int targetPosition() {
        return targetPosition;
    }

    int tolerance() {
        return tolerance;
    }

    int currentAlertMilliamps() {
        return currentAlertMilliamps;
    }

    PidLoop loop(RunMode loopMode) {
        return loopMode == RunMode.POSITION ? positionLoop : velocityLoop;
    }

    void configure(RunMode newMode, boolean floatAtZero) {
        if (newMode != mode) {
            resetLoops();
            mode = newMode;
        }
        this.floatAtZero = floatAtZero;
    }

    /** Energising a channel in position mode needs a target, or the firmware refuses. */
    void enable(boolean on) {
        if (on && mode == RunMode.POSITION && !targetPositionSet) {
            throw new CommandRejected(NackReason.MOTOR_NOT_CONFIG_BEFORE_ENABLED);
        }
        if (!on) {
            resetLoops();
        }
        enabled = on;
    }

    void setConstantPower(int power) {
        constantPower = power;
    }

    void setTargetVelocity(int velocity) {
        targetVelocity = velocity;
    }

    void setTargetPosition(int target, int newTolerance) {
        targetPosition = target;
        tolerance = newTolerance;
        targetPositionSet = true;
    }

    void setCurrentAlertMilliamps(int milliamps) {
        currentAlertMilliamps = milliamps;
    }

    void resetEncoder() {
        MotorPlant plant = port.device();
        encoderOrigin = plant == null ? 0 : plant.encoderCounts();
    }

    /** Encoder counts since the last reset; an open port has no encoder and reads 0. */
    long position() {
        MotorPlant plant = port.device();
        return plant == null ? 0 : plant.encoderCounts() - encoderOrigin;
    }

    /** Encoder velocity in counts per second. */
    double velocity() {
        MotorPlant plant = port.device();
        return plant == null ? 0 : plant.encoderCountsPerSecond();
    }

    /** Outside position mode there is no target to be at, so the channel never reports busy. */
    boolean atTarget() {
        return mode != RunMode.POSITION || Math.abs(targetPosition - position()) <= tolerance;
    }

    boolean overCurrent() {
        return currentMilliamps() > currentAlertMilliamps;
    }

    int currentMilliamps() {
        MotorPlant plant = port.device();
        if (plant == null) {
            return 0;
        }
        return (int) Math.max(0, Math.min(Short.MAX_VALUE, Math.round(plant.currentAmps() * 1000.0)));
    }

    /** One firmware tick: sample the encoder, run the control law and drive the output stage. */
    void tick(double batteryVolts, double dtSeconds) {
        MotorPlant plant = port.device();
        if (!enabled || (mode == RunMode.POSITION && !targetPositionSet)) {
            resetLoops();
            if (plant != null) {
                plant.floatTerminals();
            }
            return;
        }
        int power = (int) Math.round(Math.max(-FULL_POWER, Math.min(FULL_POWER, regulate(dtSeconds))));
        if (plant == null) {
            if (power != 0) {
                diagnostics.motorDrivenWhileUnbound(index);
            }
        } else if (power != 0) {
            plant.applyVoltage(power / FULL_POWER * batteryVolts);
        } else if (floatAtZero) {
            plant.floatTerminals();
        } else {
            plant.applyVoltage(0);
        }
    }

    /** De-energises the channel and forgets what it was asked to do. */
    void failSafe() {
        enabled = false;
        constantPower = 0;
        targetVelocity = 0;
        resetLoops();
        MotorPlant plant = port.device();
        if (plant != null) {
            plant.floatTerminals();
        }
    }

    private double regulate(double dtSeconds) {
        return switch (mode) {
            case OPEN_LOOP -> constantPower;
            case VELOCITY -> velocityLoop.update(targetVelocity - velocity(), targetVelocity, dtSeconds, FULL_POWER);
            case POSITION -> {
                double limit = Math.abs(targetVelocity);
                double goal = positionLoop.update(targetPosition - position(), 0, dtSeconds, limit);
                goal = Math.max(-limit, Math.min(limit, goal));
                yield velocityLoop.update(goal - velocity(), goal, dtSeconds, FULL_POWER);
            }
        };
    }

    private void resetLoops() {
        velocityLoop.reset();
        positionLoop.reset();
    }
}
