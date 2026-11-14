package dev.ftcsim.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.ui.input.GamepadRouting.Resolved;
import dev.ftcsim.ui.input.GamepadRouting.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

class GamepadRoutingTest {
    private static PadReading pad(int id, String guid) {
        return new PadReading(id, "pad " + id, guid, true, new float[6], new boolean[15]);
    }

    private static PadReading unmapped(int id) {
        return new PadReading(id, "mystery " + id, "x", false, new float[0], new boolean[0]);
    }

    private static GamepadConfig config(PadAssignment first, PadAssignment second) {
        return new GamepadConfig(GamepadConfig.Pad.DEFAULT.withAssignment(first), GamepadConfig.Pad.DEFAULT.withAssignment(second), KeyboardMap.DEFAULT);
    }

    private static Resolved[] resolve(GamepadConfig config, PadReading... pads) {
        return GamepadRouting.resolve(config, List.of(pads));
    }

    @Test
    void automaticGamepadsTakeTheControllersInTheOrderOfTheirJoystickSlots() {
        Resolved[] resolved = resolve(GamepadConfig.DEFAULT, pad(5, "a"), pad(2, "b"), pad(9, "c"));

        assertEquals(Source.CONTROLLER, resolved[0].source());
        assertEquals(2, resolved[0].padId());
        assertEquals(5, resolved[1].padId());
        assertNull(resolved[0].problem());
    }

    @Test
    void withoutControllersGamepadOneFallsBackToTheKeyboardAndGamepadTwoHasNothing() {
        Resolved[] resolved = resolve(GamepadConfig.DEFAULT);

        assertEquals(Source.KEYBOARD, resolved[0].source());
        assertEquals(Source.NONE, resolved[1].source());
        assertNull(resolved[1].problem(), "an unassigned gamepad is normal, not a problem");
    }

    @Test
    void withOneControllerGamepadOneGetsItAndTheKeyboardIsLeftOut() {
        Resolved[] resolved = resolve(GamepadConfig.DEFAULT, pad(0, "a"));

        assertEquals(Source.CONTROLLER, resolved[0].source());
        assertEquals(Source.NONE, resolved[1].source());
    }

    @Test
    void aControllerNamedInTheSettingsIsFoundByModelWhateverSlotItIsInNow() {
        GamepadConfig config = config(PadAssignment.AUTOMATIC, PadAssignment.controller("wanted", "Wanted pad"));

        Resolved[] resolved = resolve(config, pad(0, "other"), pad(3, "wanted"));

        assertEquals(3, resolved[1].padId(), "gamepad 2 is the one it was set to");
        assertEquals(0, resolved[0].padId(), "automatic takes what is left");
    }

    @Test
    void explicitControllersAreServedBeforeAutomaticOnesSoNoControllerIsStolen() {
        GamepadConfig config = config(PadAssignment.AUTOMATIC, PadAssignment.controller("only", "Only pad"));

        Resolved[] resolved = resolve(config, pad(0, "only"));

        assertEquals(Source.CONTROLLER, resolved[1].source());
        assertEquals(Source.KEYBOARD, resolved[0].source(), "gamepad 1 finds no controller left and uses the keyboard");
    }

    @Test
    void twoControllersOfTheSameModelServeTwoGamepadsInSlotOrder() {
        GamepadConfig config = config(PadAssignment.controller("same", "Pad"), PadAssignment.controller("same", "Pad"));

        Resolved[] resolved = resolve(config, pad(4, "same"), pad(1, "same"));

        assertEquals(1, resolved[0].padId());
        assertEquals(4, resolved[1].padId());
        assertNull(resolved[0].problem());
        assertNull(resolved[1].problem());
    }

    @Test
    void oneControllerCannotDriveBothGamepadsAndTheConflictIsSaidOnBoth() {
        GamepadConfig config = config(PadAssignment.controller("same", "Pad"), PadAssignment.controller("same", "Pad"));

        Resolved[] resolved = resolve(config, pad(0, "same"));

        assertEquals(Source.CONTROLLER, resolved[0].source());
        assertEquals(Source.NONE, resolved[1].source(), "the second claim on the same device gets nothing");
        assertNotNull(resolved[0].problem());
        assertNotNull(resolved[1].problem());
        assertTrue(resolved[1].problem().contains("same controller"));
    }

    @Test
    void theKeyboardCanDriveOnlyOneGamepad() {
        Resolved[] resolved = resolve(config(PadAssignment.KEYBOARD, PadAssignment.KEYBOARD));

        assertEquals(Source.KEYBOARD, resolved[0].source());
        assertEquals(Source.NONE, resolved[1].source());
        assertNotNull(resolved[0].problem());
        assertNotNull(resolved[1].problem());
        assertTrue(resolved[1].problem().contains("keyboard"));
    }

    @Test
    void aGamepadSetToNoneListensToNothingEvenWithControllersPluggedIn() {
        Resolved[] resolved = resolve(config(PadAssignment.NONE, PadAssignment.AUTOMATIC), pad(0, "a"));

        assertEquals(Source.NONE, resolved[0].source());
        assertNull(resolved[0].problem());
        assertEquals(0, resolved[1].padId(), "gamepad 2 may still use the controller");
    }

    @Test
    void aControllerWithoutAGamepadMappingIsNeverUsed() {
        Resolved[] resolved = resolve(GamepadConfig.DEFAULT, unmapped(0));

        assertEquals(Source.KEYBOARD, resolved[0].source());
    }

    @Test
    void aNamedControllerThatIsNotConnectedSaysSoWithoutCallingItAProblem() {
        GamepadConfig config = config(PadAssignment.controller("gone", "Old pad"), PadAssignment.AUTOMATIC);

        Resolved[] resolved = resolve(config, pad(0, "other"));

        assertEquals(Source.NONE, resolved[0].source());
        assertEquals("nothing (Old pad is not connected)", resolved[0].description());
        assertFalse(resolved[0].hasProblem());
        assertEquals(0, resolved[1].padId());
    }
}
