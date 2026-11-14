package dev.ftcsim.ui.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import org.junit.jupiter.api.Test;

class PadMappingTest {
    private static final int BUTTONS = 15;
    private static final float STICK_DEADZONE = GamepadConfig.Pad.DEFAULT_STICK_DEADZONE;

    private static PadReading pad(float[] axes, boolean[] buttons) {
        return new PadReading(0, "test pad", "guid-0", true, axes, buttons);
    }

    private static float[] rest() {
        // GLFW reports a released trigger as -1.
        return new float[] {0f, 0f, 0f, 0f, -1f, -1f};
    }

    @Test
    void aPadAtRestIsNeutral() {
        assertEquals(GamepadState.NEUTRAL, PadMapping.toState(pad(rest(), new boolean[BUTTONS])));
    }

    @Test
    void sticksKeepFtcsSignsAndPassThroughTheDeadZone() {
        float[] axes = rest();
        axes[0] = 1f;
        axes[1] = 0f;
        axes[2] = 0.05f;
        axes[3] = 0.03f;

        GamepadState state = PadMapping.toState(pad(axes, new boolean[BUTTONS]));

        assertEquals(1f, state.leftStickX(), 1e-6f);
        assertEquals(0f, state.rightStickX(), "inside the dead zone");
        assertEquals(0f, state.rightStickY(), "inside the dead zone");

        axes[1] = -0.5f;
        axes[0] = 0f;
        float expected = (0.5f - STICK_DEADZONE) / (1f - STICK_DEADZONE);
        assertEquals(-expected, PadMapping.toState(pad(axes, new boolean[BUTTONS])).leftStickY(), 1e-6f, "up is negative, as on a real gamepad");
    }

    @Test
    void theDeadZoneOfASticksIsRoundNotSquare() {
        float[] axes = rest();
        axes[0] = 0.07f;
        axes[1] = 0.07f;

        GamepadState state = PadMapping.toState(pad(axes, new boolean[BUTTONS]), 0.1f, 0.02f);

        assertEquals(0f, state.leftStickX(), "0.07 on both axes has a length of 0.099, which is inside 0.1");
        assertEquals(0f, state.leftStickY());
    }

    @Test
    void aWiderDeadZoneIsAppliedAndStillReachesFullTravel() {
        float[] axes = rest();
        axes[0] = 0.3f;
        assertEquals(0f, PadMapping.toState(pad(axes, new boolean[BUTTONS]), 0.4f, 0.02f).leftStickX());

        axes[0] = 1f;
        assertEquals(1f, PadMapping.toState(pad(axes, new boolean[BUTTONS]), 0.4f, 0.02f).leftStickX(), 1e-6f);
    }

    @Test
    void aStickPushedPastTheCircleIsStillAtMostOne() {
        float[] axes = rest();
        axes[0] = 1f;
        axes[1] = 1f;

        GamepadState state = PadMapping.toState(pad(axes, new boolean[BUTTONS]));

        assertEquals(1.0, Math.hypot(state.leftStickX(), state.leftStickY()), 1e-6);
    }

    @Test
    void triggersMoveFromMinusOneToOneOntoZeroToOneAfterTheirDeadZone() {
        float[] axes = rest();
        axes[4] = 1f;
        axes[5] = 0f;

        GamepadState state = PadMapping.toState(pad(axes, new boolean[BUTTONS]), STICK_DEADZONE, 0f);

        assertEquals(1f, state.leftTrigger());
        assertEquals(0.5f, state.rightTrigger());

        axes[5] = -0.96f;
        assertEquals(0f, PadMapping.toState(pad(axes, new boolean[BUTTONS]), STICK_DEADZONE, 0.05f).rightTrigger(), "a trigger that barely moved");
    }

    @Test
    void buttonsFollowGlfwsXboxLayout() {
        boolean[] buttons = new boolean[BUTTONS];
        buttons[0] = true;
        buttons[3] = true;
        buttons[5] = true;
        buttons[7] = true;
        buttons[12] = true;

        GamepadState state = PadMapping.toState(pad(rest(), buttons));

        assertTrue(state.a() && state.y() && state.rightBumper() && state.start() && state.dpadRight());
        assertFalse(state.b() || state.x() || state.leftBumper() || state.back() || state.dpadUp() || state.dpadLeft());
    }

    @Test
    void aControllerWithoutAGamepadMappingReadsAsUntouched() {
        PadReading unmapped = new PadReading(2, "unknown", "guid-2", false, new float[0], new boolean[0]);

        assertEquals(GamepadState.NEUTRAL, PadMapping.toState(unmapped));
        assertArrayEquals(new float[6], PadMapping.rawAxes(unmapped));
    }

    @Test
    void theRawAxesAreWhatTheControllerReportsBeforeAnyDeadZone() {
        float[] axes = {0.04f, -0.02f, 0.5f, -0.5f, 1f, -1f};

        assertArrayEquals(new float[] {0.04f, -0.02f, 0.5f, -0.5f, 1f, 0f}, PadMapping.rawAxes(pad(axes, new boolean[BUTTONS])), 1e-6f);
    }
}
