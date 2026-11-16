package dev.ftcsim.game.decode;

import java.util.ArrayList;
import java.util.List;

/**
 * The balls on one CLASSIFIER ramp as a queue that is moved by rule instead of by contacts. A ball joins at the top,
 * rolls downhill, and stops against the ball ahead of it or against the closed gate. While the gate is open the whole
 * row rolls out of the lower end. A ball that arrives when nine are already on the ramp rolls over their tops and
 * leaves over the gate. Pure arithmetic in fixed steps, so it behaves the same on every run.
 */
final class RampLine {

    static final double DT = 1.0 / 240.0;

    private static final double GRAVITY = 9.81;
    // A solid ball rolling down a slope without slipping accelerates at 5/7 of what a sliding block would.
    private static final double ROLL_ACCELERATION = 5.0 / 7.0 * GRAVITY * RampGeometry.SIN;
    private static final double OVERFLOW_ACCELERATION = 1.2;
    private static final double ENTRY_SPEED = 0.5;
    // A ball this far past the closed gate's stop is already through and is not held back by a gate that closes now.
    private static final double HOLD_BAND = 0.03;
    private static final double SLOT = RampGeometry.SLOT;

    /** A ball on the ramp: its place along the line and its speed downhill. */
    static final class Mover {
        private final int id;
        private final boolean overflow;
        private double s = RampGeometry.ENTRY;
        private double speed = ENTRY_SPEED;

        private Mover(int id, boolean overflow) {
            this.id = id;
            this.overflow = overflow;
        }

        int id() {
            return id;
        }

        boolean overflow() {
            return overflow;
        }

        double s() {
            return s;
        }

        double speed() {
            return speed;
        }
    }

    /** A ball that left the lower end of the ramp, with the speed it left at. */
    record Exit(int id, double speed, boolean overflow) {}

    private final List<Mover> stack = new ArrayList<>();
    private final List<Mover> over = new ArrayList<>();

    /**
     * A ball passed the SQUARE.
     *
     * @return true if it joins the ramp as CLASSIFIED, false if the ramp is full and it overflows
     */
    boolean enter(int id) {
        boolean classified = stack.size() < RampGeometry.CAPACITY;
        (classified ? stack : over).add(new Mover(id, !classified));
        return classified;
    }

    /** Moves every ball by one step and returns the ones that left the lower end, in the order they left. */
    List<Exit> advance(boolean gateOpen) {
        List<Exit> exits = new ArrayList<>();
        advanceRow(stack, ROLL_ACCELERATION, gateOpen);
        while (!stack.isEmpty() && stack.get(0).s > RampGeometry.EXIT) {
            Mover gone = stack.remove(0);
            exits.add(new Exit(gone.id, gone.speed, false));
        }
        advanceRow(over, OVERFLOW_ACCELERATION, true);
        while (!over.isEmpty() && over.get(0).s > RampGeometry.OVERFLOW_EXIT) {
            Mover gone = over.remove(0);
            exits.add(new Exit(gone.id, gone.speed, true));
        }
        return exits;
    }

    private static void advanceRow(List<Mover> row, double acceleration, boolean gateOpen) {
        for (int i = 0; i < row.size(); i++) {
            Mover ball = row.get(i);
            ball.speed += acceleration * DT;
            ball.s += ball.speed * DT;
            if (i > 0) {
                Mover ahead = row.get(i - 1);
                double limit = ahead.s - SLOT;
                if (ball.s > limit) {
                    ball.s = limit;
                    ball.speed = Math.min(ball.speed, ahead.speed);
                }
            } else if (!gateOpen && ball.s >= RampGeometry.FIRST_SLOT && ball.s < RampGeometry.FIRST_SLOT + HOLD_BAND) {
                ball.s = RampGeometry.FIRST_SLOT;
                ball.speed = 0;
            }
        }
    }

    /** Drops a ball that is no longer in the world. */
    void remove(int id) {
        stack.removeIf(ball -> ball.id == id);
        over.removeIf(ball -> ball.id == id);
    }

    boolean holds(int id) {
        return stack.stream().anyMatch(ball -> ball.id == id) || over.stream().anyMatch(ball -> ball.id == id);
    }

    /** The CLASSIFIED balls, nearest the gate first, which is the order of the indices of the PATTERN. */
    List<Mover> classified() {
        return List.copyOf(stack);
    }

    List<Mover> overflowing() {
        return List.copyOf(over);
    }

    /** True while any ball is still rolling on the ramp. */
    boolean moving() {
        return !over.isEmpty() || stack.stream().anyMatch(ball -> ball.speed != 0);
    }
}
