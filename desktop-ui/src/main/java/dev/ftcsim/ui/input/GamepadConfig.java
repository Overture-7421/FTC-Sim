package dev.ftcsim.ui.input;

import java.util.Objects;

/**
 * Everything a person can set about the two gamepads: where each listens, how large its dead zones are, and the keys
 * of the keyboard that can stand in for one of them.
 */
public record GamepadConfig(Pad first, Pad second, KeyboardMap keyboard) {

    /** One gamepad's settings. Dead zones are fractions of full travel. */
    public record Pad(PadAssignment assignment, float stickDeadzone, float triggerDeadzone) {
        public static final float DEFAULT_STICK_DEADZONE = 0.08f;
        public static final float DEFAULT_TRIGGER_DEADZONE = 0.02f;
        public static final float MAX_STICK_DEADZONE = 0.5f;
        public static final float MAX_TRIGGER_DEADZONE = 0.5f;

        public static final Pad DEFAULT = new Pad(PadAssignment.AUTOMATIC, DEFAULT_STICK_DEADZONE, DEFAULT_TRIGGER_DEADZONE);

        public Pad {
            Objects.requireNonNull(assignment, "assignment");
            stickDeadzone = clamp(stickDeadzone, MAX_STICK_DEADZONE);
            triggerDeadzone = clamp(triggerDeadzone, MAX_TRIGGER_DEADZONE);
        }

        public Pad withAssignment(PadAssignment changed) {
            return new Pad(changed, stickDeadzone, triggerDeadzone);
        }

        public Pad withStickDeadzone(float changed) {
            return new Pad(assignment, changed, triggerDeadzone);
        }

        public Pad withTriggerDeadzone(float changed) {
            return new Pad(assignment, stickDeadzone, changed);
        }

        private static float clamp(float value, float max) {
            return Float.isNaN(value) ? 0f : Math.min(max, Math.max(0f, value));
        }
    }

    public static final GamepadConfig DEFAULT = new GamepadConfig(Pad.DEFAULT, Pad.DEFAULT, KeyboardMap.DEFAULT);

    public GamepadConfig {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(keyboard, "keyboard");
    }

    /** The settings of gamepad 1 or 2. */
    public Pad pad(int gamepad) {
        return switch (gamepad) {
            case 1 -> first;
            case 2 -> second;
            default -> throw new IllegalArgumentException("gamepad is 1 or 2: " + gamepad);
        };
    }

    public GamepadConfig withPad(int gamepad, Pad changed) {
        return switch (gamepad) {
            case 1 -> new GamepadConfig(changed, second, keyboard);
            case 2 -> new GamepadConfig(first, changed, keyboard);
            default -> throw new IllegalArgumentException("gamepad is 1 or 2: " + gamepad);
        };
    }

    public GamepadConfig withKeyboard(KeyboardMap changed) {
        return new GamepadConfig(first, second, changed);
    }
}
