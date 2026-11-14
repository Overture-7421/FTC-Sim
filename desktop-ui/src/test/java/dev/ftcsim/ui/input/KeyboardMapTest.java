package dev.ftcsim.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeyboardMapTest {
    private static final KeyboardMap DEFAULT = KeyboardMap.DEFAULT;

    @Test
    void noKeysIsANeutralGamepad() {
        assertEquals(GamepadState.NEUTRAL, DEFAULT.state(Set.of()));
    }

    @Test
    void wasdIsTheLeftStickWithUpAndLeftNegative() {
        GamepadState forward = DEFAULT.state(Set.of(KeyEvent.VK_W));
        assertEquals(-1f, forward.leftStickY());
        assertEquals(0f, forward.leftStickX());

        GamepadState backLeft = DEFAULT.state(Set.of(KeyEvent.VK_S, KeyEvent.VK_A));
        assertEquals(1f / (float) Math.sqrt(2), backLeft.leftStickY(), 1e-6f);
        assertEquals(-1f / (float) Math.sqrt(2), backLeft.leftStickX(), 1e-6f);
    }

    @Test
    void opposingKeysCancelAndADiagonalStaysOnTheUnitCircle() {
        assertEquals(0f, DEFAULT.state(Set.of(KeyEvent.VK_A, KeyEvent.VK_D)).leftStickX());

        GamepadState diagonal = DEFAULT.state(Set.of(KeyEvent.VK_W, KeyEvent.VK_D));
        assertEquals(1.0, Math.hypot(diagonal.leftStickX(), diagonal.leftStickY()), 1e-6);
    }

    @Test
    void theArrowKeysAreTheRightStick() {
        assertEquals(1f, DEFAULT.state(Set.of(KeyEvent.VK_RIGHT)).rightStickX());
        assertEquals(-1f, DEFAULT.state(Set.of(KeyEvent.VK_UP)).rightStickY());
    }

    @Test
    void buttonsAndTriggersAreMapped() {
        GamepadState pressed = DEFAULT.state(Set.of(
                KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_J, KeyEvent.VK_I, KeyEvent.VK_Q, KeyEvent.VK_E, KeyEvent.VK_Z,
                KeyEvent.VK_C, KeyEvent.VK_T, KeyEvent.VK_G, KeyEvent.VK_F, KeyEvent.VK_H, KeyEvent.VK_X, KeyEvent.VK_V,
                KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3));

        assertTrue(pressed.a() && pressed.b() && pressed.x() && pressed.y());
        assertTrue(pressed.leftBumper() && pressed.rightBumper());
        assertEquals(1f, pressed.leftTrigger());
        assertEquals(1f, pressed.rightTrigger());
        assertTrue(pressed.dpadUp() && pressed.dpadDown() && pressed.dpadLeft() && pressed.dpadRight());
        assertTrue(pressed.leftStickButton() && pressed.rightStickButton());
        assertTrue(pressed.start() && pressed.back() && pressed.guide());
    }

    @Test
    void everyControlHasADefaultKeyAndNoKeyIsUsedTwice() {
        Map<PadControl, Integer> bindings = DEFAULT.bindings();

        assertEquals(PadControl.values().length, bindings.size());
        assertTrue(bindings.values().stream().allMatch(key -> key > 0), "every control has a key");
        assertEquals(bindings.size(), Set.copyOf(bindings.values()).size());
        assertTrue(DEFAULT.conflicts().isEmpty());
    }

    @Test
    void aBindingIsChangedInACopyAndTheNewKeyDrivesTheControl() {
        KeyboardMap changed = DEFAULT.with(PadControl.A, KeyEvent.VK_P);

        assertEquals(KeyEvent.VK_K, DEFAULT.keyOf(PadControl.A), "the original is unchanged");
        assertEquals(KeyEvent.VK_P, changed.keyOf(PadControl.A));
        assertTrue(changed.state(Set.of(KeyEvent.VK_P)).a());
        assertFalse(changed.state(Set.of(KeyEvent.VK_K)).a(), "the old key no longer does it");
        assertFalse(changed.isMapped(KeyEvent.VK_K));
        assertTrue(changed.isMapped(KeyEvent.VK_P));
    }

    @Test
    void aControlWithoutAKeyCannotBePressed() {
        KeyboardMap cleared = DEFAULT.with(PadControl.A, 0);

        assertEquals(0, cleared.keyOf(PadControl.A));
        assertFalse(cleared.state(Set.of(0)).a());
        assertFalse(cleared.isMapped(0));
    }

    @Test
    void aKeyUsedByTwoControlsIsAConflictAndDrivesBoth() {
        KeyboardMap clash = DEFAULT.with(PadControl.B, KeyEvent.VK_K);

        assertEquals(Map.of(KeyEvent.VK_K, List.of(PadControl.A, PadControl.B)), clash.conflicts());
        GamepadState state = clash.state(Set.of(KeyEvent.VK_K));
        assertTrue(state.a() && state.b());
    }

    @Test
    void everyKeyThatDoesSomethingIsReportedAsMappedAndTheOthersAreNot() {
        assertTrue(DEFAULT.isMapped(KeyEvent.VK_W));
        assertTrue(DEFAULT.isMapped(KeyEvent.VK_LEFT));
        assertFalse(DEFAULT.isMapped(KeyEvent.VK_TAB), "Tab must keep moving focus");
        assertFalse(DEFAULT.isMapped(KeyEvent.VK_ESCAPE));
        assertFalse(DEFAULT.isMapped(KeyEvent.VK_SPACE));
    }

    @Test
    void keysThatMoveFocusPressButtonsOrCancelCannotBeBound() {
        for (int key : new int[] {KeyEvent.VK_TAB, KeyEvent.VK_ESCAPE, KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_BACK_SPACE,
                KeyEvent.VK_DELETE, KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_WINDOWS}) {
            assertTrue(KeyboardMap.reservedReason(key).isPresent(), KeyEvent.getKeyText(key));
        }
        assertEquals(Optional.empty(), KeyboardMap.reservedReason(KeyEvent.VK_W));
        assertEquals(Optional.empty(), KeyboardMap.reservedReason(KeyEvent.VK_UP));
    }

    @Test
    void keysAreNamedAsOnTheKeyboard() {
        assertEquals("W", KeyboardMap.keyName(KeyEvent.VK_W));
        assertEquals("Not set", KeyboardMap.keyName(0));
    }
}
