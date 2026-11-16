package dev.ftcsim.field;

import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.OptionalInt;

/**
 * A field that is running: the state of its game rules next to the state of its bodies in the physics world.
 * Everything here is called from the simulation thread, in simulated-time order.
 */
public interface FieldRun {

    /**
     * Applies the rules of the game to what the world just did, once after every physics step: pieces that
     * entered a goal, structures that moved, pieces a human put into play.
     */
    void afterStep(WorldSnapshot snapshot);

    /** The match moved into a new phase at {@code timeNanos} of simulated time. */
    void enterPhase(MatchPhase phase, long timeNanos);

    /** A person at the field does one of the actions the field's description lists. */
    void perform(String humanActionId);

    /**
     * Hands out a piece of the given kind that the field holds out of play, such as a preload or a piece a human
     * is about to feed in. The piece leaves the reserve and is not in the physics world; whoever takes it must put
     * it into the world with its id (for instance through a mechanism's storage) or it is lost to the game.
     *
     * @return the piece's entity id, or empty when none of that kind is left in reserve
     */
    OptionalInt takeFromReserve(int kind);

    /**
     * The kinds of the pieces the robot starts holding, one entry each, in the order they are loaded. A robot loads as
     * many as its storage takes; none by default.
     */
    default int[] robotPreloads() {
        return new int[0];
    }

    /** The points of the robot's alliance for the pieces and structures as they are now. */
    Score score();
}
