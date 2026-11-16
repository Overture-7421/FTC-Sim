package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.EntityIds;
import java.util.List;

/** Looks at the field the way a referee does when the match period ends: the robot, the ramp and the depot. */
final class Assessor {

    /**
     * What the referee sees.
     *
     * @param leftLaunchLines whether the robot is clear of every launch line
     * @param patternMatches  how many of the CLASSIFIED artifacts on the ramp have the colour of their index
     * @param depotArtifacts  how many artifacts are over the alliance's depot
     * @param baseStatus      0 when the robot is not on its base, 1 when partly and 2 when fully
     */
    record Assessment(boolean leftLaunchLines, int patternMatches, int depotArtifacts, int baseStatus) {}

    private static final double ON_THE_FLOOR = 0.25;

    private final Alliance alliance;
    private final Layout layout;
    private final double halfLength;
    private final double halfWidth;

    Assessor(Alliance alliance, Layout layout, double robotLengthMeters, double robotWidthMeters) {
        this.alliance = alliance;
        this.layout = layout;
        this.halfLength = 0.5 * robotLengthMeters;
        this.halfWidth = 0.5 * robotWidthMeters;
    }

    Assessment assess(WorldSnapshot snapshot, Classifier own) {
        boolean left = false;
        int base = 0;
        for (BodyState body : snapshot.bodies()) {
            if (body.entityId() == EntityIds.CHASSIS) {
                Zones.Footprint robot = footprint(body);
                left = !Zones.isOverLaunchLine(robot);
                base = Zones.baseStatus(robot, alliance);
            }
        }
        return new Assessment(left, patternMatches(own), depotArtifacts(snapshot), base);
    }

    private Zones.Footprint footprint(BodyState body) {
        double yaw = StrictMath.atan2(
                2 * (body.qw() * body.qz() + body.qx() * body.qy()),
                1 - 2 * (body.qy() * body.qy() + body.qz() * body.qz()));
        return new Zones.Footprint(body.x(), body.y(), yaw, halfLength, halfWidth);
    }

    // Only artifacts that the gate is holding score for the PATTERN; with the gate held open they are on their way out.
    private int patternMatches(Classifier own) {
        if (own.isGateOpen()) {
            return 0;
        }
        List<Integer> ramp = own.restingIds();
        int matches = 0;
        for (int i = 0; i < ramp.size(); i++) {
            if (layout.kindOf(ramp.get(i)) == layout.motif().kindAtIndex(i + 1)) {
                matches++;
            }
        }
        return matches;
    }

    private int depotArtifacts(WorldSnapshot snapshot) {
        int count = 0;
        for (BodyState body : snapshot.bodies()) {
            if (Kinds.isArtifact(body.kind()) && body.z() < ON_THE_FLOOR
                    && Zones.isOverDepot(body.x(), body.y(), Dimensions.ARTIFACT_RADIUS, alliance)) {
                count++;
            }
        }
        return count;
    }
}
