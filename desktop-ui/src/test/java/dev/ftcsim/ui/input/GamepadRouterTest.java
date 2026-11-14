package dev.ftcsim.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.ui.render.OrbitCamera;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GamepadRouterTest {
    private record Sent(int gamepad, GamepadState state) {}

    private final List<Sent> sent = new ArrayList<>();
    private final ViewportInput input = new ViewportInput(new OrbitCamera());
    private final GamepadRouter router = new GamepadRouter((gamepad, state) -> sent.add(new Sent(gamepad, state)), input.keys());

    private static PadReading pad(int id, float leftX, boolean a) {
        return pad(id, "guid-" + id, leftX, a);
    }

    private static PadReading pad(int id, String guid, float leftX, boolean a) {
        boolean[] buttons = new boolean[15];
        buttons[0] = a;
        return new PadReading(id, "pad " + id, guid, true, new float[] {leftX, 0f, 0f, 0f, -1f, -1f}, buttons);
    }

    private GamepadState last(int gamepad) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).gamepad() == gamepad) {
                return sent.get(i).state();
            }
        }
        throw new AssertionError("nothing was sent to gamepad " + gamepad);
    }

    private void holdKeyboard(int keyCode) {
        input.focusChanged(true);
        input.keyPressed(keyCode);
    }

    @Test
    void bothGamepadsStartNeutralAndOnlyChangesAreSentAfterThat() {
        router.refresh();
        router.refresh();
        router.padsPolled(List.of());

        assertEquals(List.of(new Sent(1, GamepadState.NEUTRAL), new Sent(2, GamepadState.NEUTRAL)), sent);
    }

    @Test
    void theKeyboardDrivesGamepadOneWhileNoControllerIsAssignedToIt() {
        router.refresh();
        sent.clear();

        holdKeyboard(KeyEvent.VK_W);

        assertEquals(-1f, last(1).leftStickY());
        assertEquals(1, sent.size(), "gamepad 2 did not change");

        input.keyReleased(KeyEvent.VK_W);
        assertEquals(GamepadState.NEUTRAL, last(1));
    }

    @Test
    void controllersTakeGamepadOneAndTwoInOrderAndTheKeyboardStepsAside() {
        router.padsPolled(List.of(pad(3, 1f, false), pad(1, 0f, true), pad(5, 0f, false)));

        assertEquals(1, router.resolutions()[0].padId(), "the lowest id is connected first");
        assertEquals(3, router.resolutions()[1].padId());
        assertEquals(1f, last(2).leftStickX());
        assertTrue(last(1).a());

        holdKeyboard(KeyEvent.VK_W);
        assertEquals(0f, last(1).leftStickY(), "gamepad 1 belongs to a controller now");
    }

    @Test
    void aControllerThatDisconnectsLeavesItsGamepadNeutralAtOnceAndFreesItsPlace() {
        router.padsPolled(List.of(pad(0, 1f, true)));
        assertEquals(1f, last(1).leftStickX());

        router.padsPolled(List.of());

        assertEquals(GamepadState.NEUTRAL, last(1));
        assertEquals(-1, router.resolutions()[0].padId());

        router.padsPolled(List.of(pad(7, 0.5f, false)));
        assertEquals(7, router.resolutions()[0].padId(), "the next controller fills the free place");
    }

    @Test
    void aControllerWithoutAMappingIsNeverAssigned() {
        PadReading unmapped = new PadReading(0, "unknown", "guid-0", false, new float[0], new boolean[0]);

        router.padsPolled(List.of(unmapped));

        assertEquals(-1, router.resolutions()[0].padId());
        assertEquals(GamepadState.NEUTRAL, last(1));
        assertEquals(1, router.controllers().size(), "it is still listed, so the Controls screen can say it is not usable");
    }

    @Test
    void inactiveInputMakesEverythingNeutralAndActiveRestoresIt() {
        router.padsPolled(List.of(pad(0, 1f, true)));
        holdKeyboard(KeyEvent.VK_D);

        router.setActive(false);
        assertEquals(GamepadState.NEUTRAL, last(1));

        router.setActive(true);
        assertEquals(1f, last(1).leftStickX());
    }

    @Test
    void losingTheKeyTargetsFocusReleasesTheKeyboardGamepadThroughTheHeldKeys() {
        holdKeyboard(KeyEvent.VK_W);
        assertNotEquals(GamepadState.NEUTRAL, last(1));

        input.focusChanged(false);

        assertEquals(GamepadState.NEUTRAL, last(1));
    }

    @Test
    void thePreviewShowsWhatTheControllerDoesEvenWhileTheRobotIsNotListening() {
        router.padsPolled(List.of(pad(0, 1f, true)));
        router.setActive(false);

        GamepadRouter.Preview preview = router.preview(1);

        assertEquals(GamepadState.NEUTRAL, last(1), "the robot gets nothing");
        assertEquals(1f, preview.state().leftStickX());
        assertTrue(preview.state().a());
        assertEquals(1f, preview.raw()[0]);
        assertEquals(GamepadRouting.Source.CONTROLLER, preview.resolved().source());
    }

    @Test
    void thePreviewOfTheKeyboardFollowsTheKeysHeldInTheTestArea() {
        holdKeyboard(KeyEvent.VK_D);
        router.setActive(false);

        GamepadRouter.Preview preview = router.preview(1);

        assertEquals(GamepadRouting.Source.KEYBOARD, preview.resolved().source());
        assertEquals(1f, preview.state().leftStickX());
        assertEquals(1f, preview.raw()[0], "a key is a stick at full travel");
    }

    @Test
    void deadZonesFromTheSettingsApplyAtOnce() {
        router.padsPolled(List.of(pad(0, 0.2f, false)));
        assertTrue(last(1).leftStickX() > 0f);

        GamepadConfig wide = GamepadConfig.DEFAULT.withPad(1, GamepadConfig.Pad.DEFAULT.withStickDeadzone(0.3f));
        router.configure(wide);

        assertEquals(0f, last(1).leftStickX(), "0.2 is inside a dead zone of 0.3");
    }

    @Test
    void reboundKeysTakeEffectAtOnce() {
        holdKeyboard(KeyEvent.VK_P);
        assertEquals(GamepadState.NEUTRAL, last(1), "P is not bound to anything yet");

        router.configure(GamepadConfig.DEFAULT.withKeyboard(KeyboardMap.DEFAULT.with(PadControl.A, KeyEvent.VK_P)));

        assertTrue(last(1).a());
    }

    @Test
    void aGamepadSetToNoneStaysNeutralWhateverIsPluggedIn() {
        router.configure(GamepadConfig.DEFAULT.withPad(1, GamepadConfig.Pad.DEFAULT.withAssignment(PadAssignment.NONE)));

        router.padsPolled(List.of(pad(0, 1f, true)));

        assertEquals(GamepadState.NEUTRAL, last(1));
        assertEquals(1f, last(2).leftStickX(), "the controller goes to gamepad 2 instead");
    }

    @Test
    void oneControllerSetOnBothGamepadsDrivesOnlyTheFirstAndTheConflictIsReported() {
        PadAssignment same = PadAssignment.controller("guid-0", "pad 0");
        router.configure(GamepadConfig.DEFAULT
                .withPad(1, GamepadConfig.Pad.DEFAULT.withAssignment(same))
                .withPad(2, GamepadConfig.Pad.DEFAULT.withAssignment(same)));

        router.padsPolled(List.of(pad(0, 1f, true)));

        assertEquals(1f, last(1).leftStickX());
        assertEquals(GamepadState.NEUTRAL, last(2));
        assertTrue(router.resolutions()[1].hasProblem());
        assertFalse(router.preview(2).state().a());
    }
}
