package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.support.Occupancy;
import dev.ftcsim.field.support.Region;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Pivot;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One alliance's goal with its CLASSIFIER. A piece counts when it was inside the goal and then crosses the SQUARE at
 * the archway; at that moment the piece is taken out of the free physics and a {@link RampLine} carries it down the
 * ramp, and when it leaves the ramp it is a free body again. The gate is a real hinged body in the world; only
 * whether its arm is out of the way is read from it.
 */
final class Classifier {

    /** An artifact that crossed the SQUARE. */
    record Scored(Alliance alliance, boolean classified) {}

    private static final double BLEND_SECONDS = 0.04;
    private static final double BLEND_DECAY = StrictMath.exp(-RampLine.DT / BLEND_SECONDS);
    private static final double BLEND_REST_METERS = 1e-4;

    private final Alliance alliance;
    private final boolean red;
    private final PhysicsWorld world;
    private final Pivot gate;
    private final RampLine ramp = new RampLine();
    private final Occupancy goal;
    private final Occupancy square;
    // Pieces that are inside the goal and so may score by crossing the SQUARE.
    private final Set<Integer> armed = new TreeSet<>();
    // How far a ball on the ramp still is from the line it is guided along; it closes up within a fraction of a second.
    private final Map<Integer, Vector3> blends = new TreeMap<>();

    Classifier(Alliance alliance, PhysicsWorld world, Pivot gate) {
        this.alliance = alliance;
        this.red = alliance == Alliance.RED;
        this.world = world;
        this.gate = gate;
        this.goal = new Occupancy(goalVolume(red), Kinds::isArtifact);
        this.square = new Occupancy(squareVolume(red), Kinds::isArtifact);
    }

    // The box behind the front panel, long enough to reach past the archway; a ball in it fell in through the open top.
    private static Region goalVolume(boolean red) {
        Vector3 tangent = Dimensions.frontTangent();
        Vector3 normal = Dimensions.frontNormal();
        double reach = 0.04;
        double depth = 0.25;
        double x = Dimensions.FRONT_PANEL_X + tangent.x() * reach - normal.x() * depth;
        double y = Dimensions.FRONT_PANEL_Y + tangent.y() * reach - normal.y() * depth;
        double yaw = Dimensions.FRONT_NORMAL_YAW - 0.5 * Math.PI;
        return new Region.TurnedBox(
                x, red ? -y : y, 1.0, 0.5 * Dimensions.FRONT_PANEL_LENGTH + reach, depth, 0.4, red ? -yaw : yaw);
    }

    // Just outside the archway, where a ball leaves the goal and meets the top of the ramp.
    private static Region squareVolume(boolean red) {
        double y = -1.69795;
        return new Region.Box(-1.11425, red ? -y : y, 0.6, 0.11425, 0.08655, 0.25);
    }

    /** Applies the rules for one step; returns the artifacts that scored in it. */
    List<Scored> update(WorldSnapshot snapshot) {
        Occupancy.Change inGoal = goal.update(snapshot);
        Occupancy.Change inSquare = square.update(snapshot);
        List<Scored> scored = new ArrayList<>();
        for (int id : inSquare.entered()) {
            if (armed.contains(id) && !ramp.holds(id)) {
                scored.add(capture(snapshot, id));
            }
        }
        armed.addAll(inGoal.entered());
        armed.removeAll(inGoal.left());
        forgetPiecesThatLeftTheWorld(snapshot);
        for (RampLine.Exit exit : ramp.advance(isGateOpen())) {
            release(exit);
        }
        moveBallsOnTheRamp();
        return scored;
    }

    private Scored capture(WorldSnapshot snapshot, int id) {
        BodyState ball = find(snapshot, id);
        armed.remove(id);
        world.holdPiece(id);
        boolean classified = ramp.enter(id);
        double lift = classified ? 0 : RampGeometry.OVERFLOW_LIFT;
        Vector3 where = new Vector3(ball.x(), ball.y(), ball.z());
        blends.put(id, where.minus(RampGeometry.point(RampGeometry.ENTRY, lift, red)));
        return new Scored(alliance, classified);
    }

    private void release(RampLine.Exit exit) {
        blends.remove(exit.id());
        world.releasePiece(
                exit.id(),
                RampGeometry.downhill().times(exit.speed()),
                new Vector3(0, exit.speed() / Dimensions.ARTIFACT_RADIUS, 0));
    }

    private void moveBallsOnTheRamp() {
        List<RampLine.Mover> balls = new ArrayList<>(ramp.classified());
        balls.addAll(ramp.overflowing());
        for (RampLine.Mover ball : balls) {
            double lift = ball.overflow() ? RampGeometry.OVERFLOW_LIFT : 0;
            Vector3 blend = blends.getOrDefault(ball.id(), Vector3.ZERO).times(BLEND_DECAY);
            if (blend.length() < BLEND_REST_METERS) {
                blend = Vector3.ZERO;
            }
            blends.put(ball.id(), blend);
            world.moveHeldPiece(
                    ball.id(),
                    RampGeometry.point(ball.s(), lift, red).plus(blend),
                    Vector3.Y,
                    ball.s() / Dimensions.ARTIFACT_RADIUS);
        }
    }

    // Something else, such as a mechanism, took a ball off the ramp.
    private void forgetPiecesThatLeftTheWorld(WorldSnapshot snapshot) {
        List<RampLine.Mover> balls = new ArrayList<>(ramp.classified());
        balls.addAll(ramp.overflowing());
        for (RampLine.Mover ball : balls) {
            if (find(snapshot, ball.id()) == null) {
                ramp.remove(ball.id());
                blends.remove(ball.id());
            }
        }
    }

    private static BodyState find(WorldSnapshot snapshot, int id) {
        List<BodyState> bodies = snapshot.bodies();
        int low = 0;
        int high = bodies.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = Integer.compare(bodies.get(middle).entityId(), id);
            if (order == 0) {
                return bodies.get(middle);
            }
            if (order < 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return null;
    }

    boolean isGateOpen() {
        return gate.angleRadians() >= RampGeometry.GATE_OPEN_ANGLE;
    }

    /** The ids of the CLASSIFIED balls that are at rest on the ramp, the one nearest the gate first. */
    List<Integer> restingIds() {
        List<Integer> resting = new ArrayList<>();
        for (RampLine.Mover ball : ramp.classified()) {
            if (ball.speed() != 0) {
                break;
            }
            resting.add(ball.id());
        }
        return resting;
    }

    Pivot gate() {
        return gate;
    }
}
