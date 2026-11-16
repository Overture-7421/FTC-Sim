package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.physics.Vector3;

/** The human player's place at the field: where in the LOADING ZONE a piece can be put down. */
final class HumanPlayer {

    private static final double[] SLOT_X = {1.461, 1.588, 1.715};
    // Rows of pieces, starting against the perimeter next to the alliance area.
    private static final double[] SLOT_Y = {1.7215, 1.5945, 1.4675};
    private static final double DROP_HEIGHT = 0.02;
    private static final double OCCUPIED_METERS = 0.12;

    private final double side;

    HumanPlayer(Alliance alliance) {
        // The blue alliance area and its loading zone are on the +Y side of the field, the red ones on -Y.
        this.side = alliance == Alliance.BLUE ? 1 : -1;
    }

    /** The first place in the loading zone that no piece is on, or null if all are taken. */
    Vector3 freeSlot(WorldSnapshot snapshot) {
        for (double y : SLOT_Y) {
            for (double x : SLOT_X) {
                if (isFree(snapshot, x, side * y)) {
                    return new Vector3(x, side * y, Dimensions.ARTIFACT_RADIUS + DROP_HEIGHT);
                }
            }
        }
        return null;
    }

    private static boolean isFree(WorldSnapshot snapshot, double x, double y) {
        for (BodyState body : snapshot.bodies()) {
            boolean onTheFloor = body.z() < 0.3;
            if (Kinds.isArtifact(body.kind()) && onTheFloor && StrictMath.hypot(body.x() - x, body.y() - y) < OCCUPIED_METERS) {
                return false;
            }
        }
        return true;
    }
}
