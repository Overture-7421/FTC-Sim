package dev.ftcsim.game.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RampLineTest {

    private static final int STEPS_PER_SECOND = 240;

    private static List<RampLine.Exit> run(RampLine line, double seconds, boolean gateOpen) {
        List<RampLine.Exit> exits = new ArrayList<>();
        for (int i = 0; i < Math.round(seconds * STEPS_PER_SECOND); i++) {
            exits.addAll(line.advance(gateOpen));
        }
        return exits;
    }

    private static RampLine fullRamp() {
        RampLine line = new RampLine();
        for (int id = 0; id < RampGeometry.CAPACITY; id++) {
            assertTrue(line.enter(id), "ball " + id + " is CLASSIFIED");
            run(line, 0.6, false);
        }
        run(line, 3, false);
        return line;
    }

    @Test
    void nineBallsRestAgainstTheClosedGateAndTheTenthOverflowsOverThem() {
        RampLine line = fullRamp();
        List<RampLine.Mover> row = line.classified();
        assertEquals(9, row.size());
        for (int i = 0; i < row.size(); i++) {
            assertEquals(i, row.get(i).id());
            assertEquals(RampGeometry.FIRST_SLOT - i * RampGeometry.SLOT, row.get(i).s(), 1e-9);
            assertEquals(0.0, row.get(i).speed());
        }
        assertFalse(line.moving());

        assertFalse(line.enter(9), "the ramp is full");
        assertEquals(1, line.overflowing().size());
        List<RampLine.Exit> exits = run(line, 3, false);
        assertEquals(1, exits.size());
        assertEquals(9, exits.get(0).id());
        assertTrue(exits.get(0).overflow());
        assertEquals(9, line.classified().size(), "the stack was not disturbed");
    }

    @Test
    void anOpenGateLetsTheRowOutNearestBallFirstAndFasterAndFaster() {
        RampLine line = fullRamp();
        List<RampLine.Exit> exits = run(line, 3, true);
        assertEquals(9, exits.size());
        for (int i = 0; i < exits.size(); i++) {
            assertEquals(i, exits.get(i).id());
            assertFalse(exits.get(i).overflow());
            if (i > 0) {
                assertTrue(exits.get(i).speed() > exits.get(i - 1).speed());
            }
        }
        assertEquals(0.42, exits.get(0).speed(), 0.05);
        assertEquals(1.95, exits.get(8).speed(), 0.1);
        assertTrue(line.classified().isEmpty());
    }

    @Test
    void aGateThatClosesAgainHoldsBackTheBallsThatHaveNotYetLeft() {
        RampLine line = fullRamp();
        List<RampLine.Exit> flicked = run(line, 0.05, true);
        assertTrue(flicked.isEmpty());
        run(line, 0.5, false);
        assertEquals(9, line.classified().size(), "a short push is not enough to free a ball");
        assertEquals(RampGeometry.FIRST_SLOT, line.classified().get(0).s(), 1e-9);

        List<RampLine.Exit> out = run(line, 0.4, true);
        out.addAll(run(line, 3, false));
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).id());
        List<RampLine.Mover> row = line.classified();
        assertEquals(8, row.size());
        assertEquals(1, row.get(0).id());
        assertEquals(RampGeometry.FIRST_SLOT, row.get(0).s(), 1e-9);
        assertEquals(0.0, row.get(0).speed());
    }
}
