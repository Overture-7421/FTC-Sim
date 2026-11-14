package dev.ftcsim.physics;

/**
 * Seesaw hive: a rigid tray that tips between two end stops under a deterministic state machine.
 * The tray is kinematic, so pieces on it are carried and pushed by its motion but it never reacts
 * to them. Rotation about +Y by a positive angle lowers the +X end.
 */
public final class Hive {

    /** The end that is currently down, or the direction the tray is travelling. */
    public enum State { AT_NEGATIVE, MOVING_TO_POSITIVE, AT_POSITIVE, MOVING_TO_NEGATIVE }

    private final int entityId;
    private final HiveSpec spec;
    private double angle;
    private double target;

    Hive(int entityId, HiveSpec spec) {
        this.entityId = entityId;
        this.spec = spec;
        this.angle = -spec.limitRadians();
        this.target = angle;
    }

    public int entityId() {
        return entityId;
    }

    /** Orders the tray to tip so that the +X end goes down; ignored while already there. */
    public void tipPositive() {
        target = spec.limitRadians();
    }

    /** Orders the tray to tip so that the -X end goes down; ignored while already there. */
    public void tipNegative() {
        target = -spec.limitRadians();
    }

    public double angleRadians() {
        return angle;
    }

    public State state() {
        if (angle == target) {
            return angle > 0 ? State.AT_POSITIVE : State.AT_NEGATIVE;
        }
        return target > angle ? State.MOVING_TO_POSITIVE : State.MOVING_TO_NEGATIVE;
    }

    boolean isMoving() {
        return angle != target;
    }

    /** Advances towards the target at the scripted speed, landing exactly on the end stop. */
    void advance(double dt) {
        double maxDelta = spec.angularSpeedRadPerSec() * dt;
        double delta = target - angle;
        angle = Math.abs(delta) <= maxDelta ? target : angle + Math.copySign(maxDelta, delta);
    }

    HiveSpec spec() {
        return spec;
    }

    double target() {
        return target;
    }
}
