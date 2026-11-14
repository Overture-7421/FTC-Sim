package dev.ftcsim.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FieldRegistryTest {

    @Test
    void aBareFloorIsAlwaysThereAndNothingCanTakeItsPlaceOrAnIdTwice() {
        FieldRegistry fields = new FieldRegistry();
        fields.register("fake", () -> null);

        assertEquals(Optional.empty(), fields.create("none"));
        assertEquals(List.of("none", "fake"), fields.ids());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> fields.register("none", () -> null)).getMessage().contains("taken"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> fields.register("fake", () -> null)).getMessage().contains("taken"));
    }

    @Test
    void anUnknownIdIsAnErrorThatListsTheKnownOnes() {
        FieldRegistry fields = new FieldRegistry();
        fields.register("fake", () -> null);

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> fields.create("decode"));

        assertEquals("there is no field 'decode'; the fields are none, fake", unknown.getMessage());
    }
}
