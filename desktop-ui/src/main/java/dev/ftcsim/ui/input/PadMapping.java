package dev.ftcsim.ui.input;

import dev.ftcsim.contracts.robot.GamepadState;

/**
 * Turns a controller reading in GLFW's gamepad layout (the Xbox layout) into what an FTC gamepad reports: sticks from
 * -1 to 1 with up and left negative, triggers from 0 to 1. Sticks get a round dead zone, so that a stick that does not
 * rest exactly at the centre does not make the robot creep or flood the link with changes, and triggers a linear one.
 * Past a dead zone the travel that is left is stretched over the whole range, so full travel is still 1.
 */
public final class PadMapping {
    public static final int AXES = 6;
    public static final int BUTTONS = 15;

    private static final int AXIS_LEFT_X = 0;
    private static final int AXIS_LEFT_Y = 1;
    private static final int AXIS_RIGHT_X = 2;
    private static final int AXIS_RIGHT_Y = 3;
    private static final int AXIS_LEFT_TRIGGER = 4;
    private static final int AXIS_RIGHT_TRIGGER = 5;
    private static final int BUTTON_A = 0;
    private static final int BUTTON_B = 1;
    private static final int BUTTON_X = 2;
    private static final int BUTTON_Y = 3;
    private static final int BUTTON_LEFT_BUMPER = 4;
    private static final int BUTTON_RIGHT_BUMPER = 5;
    private static final int BUTTON_BACK = 6;
    private static final int BUTTON_START = 7;
    private static final int BUTTON_GUIDE = 8;
    private static final int BUTTON_LEFT_THUMB = 9;
    private static final int BUTTON_RIGHT_THUMB = 10;
    private static final int BUTTON_DPAD_UP = 11;
    private static final int BUTTON_DPAD_RIGHT = 12;
    private static final int BUTTON_DPAD_DOWN = 13;
    private static final int BUTTON_DPAD_LEFT = 14;

    private PadMapping() {}

    /** With the dead zones a gamepad starts with. */
    public static GamepadState toState(PadReading pad) {
        return toState(pad, GamepadConfig.Pad.DEFAULT_STICK_DEADZONE, GamepadConfig.Pad.DEFAULT_TRIGGER_DEADZONE);
    }

    /** A controller without a gamepad mapping cannot be interpreted and reads as untouched. */
    public static GamepadState toState(PadReading pad, float stickDeadzone, float triggerDeadzone) {
        if (!usable(pad)) {
            return GamepadState.NEUTRAL;
        }
        float[] axes = pad.axes();
        boolean[] buttons = pad.buttons();
        float[] left = stick(axes[AXIS_LEFT_X], axes[AXIS_LEFT_Y], stickDeadzone);
        float[] right = stick(axes[AXIS_RIGHT_X], axes[AXIS_RIGHT_Y], stickDeadzone);
        return new GamepadState(
                left[0], left[1], right[0], right[1],
                trigger(axes[AXIS_LEFT_TRIGGER], triggerDeadzone), trigger(axes[AXIS_RIGHT_TRIGGER], triggerDeadzone),
                buttons[BUTTON_DPAD_UP], buttons[BUTTON_DPAD_DOWN], buttons[BUTTON_DPAD_LEFT], buttons[BUTTON_DPAD_RIGHT],
                buttons[BUTTON_A], buttons[BUTTON_B], buttons[BUTTON_X], buttons[BUTTON_Y],
                buttons[BUTTON_LEFT_BUMPER], buttons[BUTTON_RIGHT_BUMPER],
                buttons[BUTTON_LEFT_THUMB], buttons[BUTTON_RIGHT_THUMB],
                buttons[BUTTON_START], buttons[BUTTON_BACK], buttons[BUTTON_GUIDE]);
    }

    /**
     * What the controller reports before any dead zone: left x and y, right x and y, then both triggers from 0 to 1.
     * All zeros when the controller cannot be interpreted.
     */
    public static float[] rawAxes(PadReading pad) {
        if (!usable(pad)) {
            return new float[AXES];
        }
        float[] axes = pad.axes();
        return new float[] {
            axes[AXIS_LEFT_X], axes[AXIS_LEFT_Y], axes[AXIS_RIGHT_X], axes[AXIS_RIGHT_Y],
            trigger(axes[AXIS_LEFT_TRIGGER], 0f), trigger(axes[AXIS_RIGHT_TRIGGER], 0f)
        };
    }

    private static boolean usable(PadReading pad) {
        return pad != null && pad.mapped() && pad.axes().length >= AXES && pad.buttons().length >= BUTTONS;
    }

    private static float[] stick(float x, float y, float deadzone) {
        float magnitude = (float) Math.hypot(x, y);
        if (magnitude < deadzone || magnitude == 0f) {
            return new float[] {0f, 0f};
        }
        float scaled = Math.min(1f, (magnitude - deadzone) / (1f - deadzone)) / magnitude;
        return new float[] {x * scaled, y * scaled};
    }

    /** GLFW reports triggers from -1 (released) to 1 (fully pressed). */
    private static float trigger(float raw, float deadzone) {
        float travel = Math.min(1f, Math.max(0f, (raw + 1f) / 2f));
        return travel < deadzone ? 0f : (travel - deadzone) / (1f - deadzone);
    }
}
