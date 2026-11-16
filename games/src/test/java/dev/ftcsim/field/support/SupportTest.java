package dev.ftcsim.field.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SupportTest {

    private static WorldSnapshot snapshot(BodyState... bodies) {
        return new WorldSnapshot(0, 0, List.of(bodies));
    }

    private static BodyState at(int id, int kind, float x, float y, float z) {
        return new BodyState(id, kind, x, y, z, 0, 0, 0, 1);
    }

    @Test
    void theReserveHandsOutPiecesOfAKindInTheOrderTheyWereAddedAndThenRunsOut() {
        PieceReserve reserve = new PieceReserve();
        reserve.add(7, 1002);
        reserve.add(7, 1001);
        reserve.add(8, 1003);

        assertEquals(2, reserve.count(7));
        assertEquals(OptionalInt.of(1002), reserve.take(7));
        assertEquals(OptionalInt.of(1001), reserve.take(7));
        assertEquals(OptionalInt.empty(), reserve.take(7));
        assertEquals(OptionalInt.of(1003), reserve.take(8));
        assertEquals(OptionalInt.empty(), reserve.take(9));
        assertEquals(0, reserve.count(7));
    }

    @Test
    void aTurnedBoxContainsWhatIsInsideItsTurnedEdgesAndNotWhatIsInsideTheUnturnedOnes() {
        // 2 m long, 0.2 m wide, turned a quarter turn: it now lies along the field's Y axis.
        Region box = new Region.TurnedBox(1, 1, 0, 1.0, 0.1, 0.5, Math.PI / 2);

        assertTrue(box.contains(1.0, 1.8, 0.0));
        assertFalse(box.contains(1.8, 1.0, 0.0));
        assertFalse(box.contains(1.0, 1.0, 0.6));
        assertTrue(new Region.Cylinder(0, 0, 0, 0.5, 0.5).contains(0.3, 0.3, 0.4));
        assertFalse(new Region.Cylinder(0, 0, 0, 0.5, 0.5).contains(0.4, 0.4, 0.0));
    }

    @Test
    void occupancyReportsEachEntryAndExitOnceAndOnlyForTheKindsItWatches() {
        Occupancy goal = new Occupancy(new Region.Box(0, 0, 1, 0.5, 0.5, 0.5), kind -> kind == 100);

        assertEquals(List.of(), goal.update(snapshot(at(1000, 100, 3, 3, 0), at(1001, 100, 4, 4, 0))).entered());
        Occupancy.Change enters = goal.update(snapshot(at(1000, 100, 0, 0, 1), at(1001, 100, 4, 4, 0), at(1002, 200, 0, 0, 1)));
        assertEquals(List.of(1000), enters.entered());
        assertEquals(List.of(), goal.update(snapshot(at(1000, 100, 0.1f, 0, 1))).entered(), "staying in is not entering again");
        assertEquals(Set.of(1000), goal.inside());

        Occupancy.Change leaves = goal.update(snapshot(at(1000, 100, 3, 0, 0)));
        assertEquals(List.of(1000), leaves.left());
        assertEquals(Set.of(), goal.inside());
    }

    @Test
    void aBodyThatDisappearsFromTheWorldCountsAsHavingLeft() {
        Occupancy zone = new Occupancy(new Region.Box(0, 0, 0, 1, 1, 1));
        zone.update(snapshot(at(1000, 5, 0, 0, 0)));

        assertEquals(List.of(1000), zone.update(snapshot()).left());
    }
}
