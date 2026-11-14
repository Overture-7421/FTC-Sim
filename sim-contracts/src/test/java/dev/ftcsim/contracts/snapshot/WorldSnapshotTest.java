package dev.ftcsim.contracts.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldSnapshotTest {

    private static BodyState body(int id) {
        return new BodyState(id, 0, 0f, 0f, 0f, 0f, 0f, 0f, 1f);
    }

    @Test
    void rejectsBodiesThatAreNotStrictlyOrderedById() {
        assertThrows(IllegalArgumentException.class, () -> new WorldSnapshot(0, 0, List.of(body(2), body(1))));
        assertThrows(IllegalArgumentException.class, () -> new WorldSnapshot(0, 0, List.of(body(1), body(1))));
    }

    @Test
    void isImmutableEvenWhenTheSourceListChanges() {
        List<BodyState> source = new ArrayList<>(List.of(body(1)));
        WorldSnapshot snapshot = new WorldSnapshot(1, 4_166_666L, source);
        source.add(body(2));

        assertEquals(1, snapshot.bodies().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.bodies().add(body(3)));
    }
}
