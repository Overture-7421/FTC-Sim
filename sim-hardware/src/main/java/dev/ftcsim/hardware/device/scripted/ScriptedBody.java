package dev.ftcsim.hardware.device.scripted;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import java.util.ArrayList;
import java.util.List;

/**
 * A chassis that drives a script instead of obeying physics: a chain of moves, each at a constant velocity in the
 * robot's own frame (forward, left, and turning), after which it stands still. Its pose at any instant is exact, so
 * a test or a demo knows the truth that a sensor model is supposed to measure. The host moves the clock with
 * {@link #advanceTo}; {@link #sample} describes that instant.
 */
public final class ScriptedBody implements BodyView {

    private record Move(long startNanos, long endNanos, double forward, double left, double yawRate, BodyPose start) {}

    /** The moves of a script, in order. */
    public static final class Script {
        private final List<Move> moves = new ArrayList<>();
        private long endNanos;
        private BodyPose pose;

        private Script(BodyPose start) {
            this.pose = start;
        }

        /** Drives for {@code seconds} at the given velocities in the robot's frame (m/s, m/s, rad/s). */
        public Script move(double seconds, double forward, double left, double yawRate) {
            long duration = Math.round(seconds * NANOS_PER_SECOND);
            moves.add(new Move(endNanos, endNanos + duration, forward, left, yawRate, pose));
            endNanos += duration;
            pose = poseOf(moves.get(moves.size() - 1), endNanos);
            return this;
        }

        public Script stay(double seconds) {
            return move(seconds, 0, 0, 0);
        }

        public ScriptedBody build() {
            return new ScriptedBody(List.copyOf(moves), pose);
        }
    }

    private static final double NANOS_PER_SECOND = 1e9;
    private static final double SMALL_ANGLE = 1e-4;

    private final List<Move> moves;
    private final BodyPose finalPose;
    private long nowNanos;

    private ScriptedBody(List<Move> moves, BodyPose finalPose) {
        this.moves = moves;
        this.finalPose = finalPose;
    }

    /** A script that starts at rest on the floor at {@code (x, y)} facing {@code heading}, at time zero. */
    public static Script startingAt(double x, double y, double heading) {
        return new Script(BodyPose.planar(0, x, y, heading, 0, 0, 0));
    }

    /** Sets the simulated time that {@link #sample} describes. */
    public ScriptedBody advanceTo(long nanos) {
        nowNanos = nanos;
        return this;
    }

    public BodyPose at(long nanos) {
        for (Move move : moves) {
            if (nanos < move.endNanos()) {
                return poseOf(move, nanos);
            }
        }
        return BodyPose.planar(nanos, finalPose.x(), finalPose.y(), finalPose.yaw(), 0, 0, 0);
    }

    @Override
    public BodyPose sample() {
        return at(nowNanos);
    }

    /** The pose after {@code nanos}: the move is the pose exponential, which follows the arc the chassis drove, not its chord. */
    private static BodyPose poseOf(Move move, long nanos) {
        double seconds = (nanos - move.startNanos()) / NANOS_PER_SECOND;
        double forward = move.forward() * seconds;
        double left = move.left() * seconds;
        double turn = move.yawRate() * seconds;
        double sine;
        double bulge;
        if (Math.abs(turn) < SMALL_ANGLE) {
            sine = 1.0 - turn * turn / 6.0;
            bulge = turn / 2.0;
        } else {
            sine = StrictMath.sin(turn) / turn;
            bulge = (1.0 - StrictMath.cos(turn)) / turn;
        }
        double alongStart = sine * forward - bulge * left;
        double acrossStart = bulge * forward + sine * left;
        double startCos = StrictMath.cos(move.start().yaw());
        double startSin = StrictMath.sin(move.start().yaw());
        double heading = move.start().yaw() + turn;
        double cos = StrictMath.cos(heading);
        double sin = StrictMath.sin(heading);
        return BodyPose.planar(
                nanos,
                move.start().x() + alongStart * startCos - acrossStart * startSin,
                move.start().y() + alongStart * startSin + acrossStart * startCos,
                heading,
                move.forward() * cos - move.left() * sin,
                move.forward() * sin + move.left() * cos,
                move.yawRate());
    }
}
