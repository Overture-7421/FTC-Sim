package dev.ftcsim.contracts.robot;

/**
 * Everything one FTC gamepad reports. Sticks run from -1 to 1 with up and left negative on the Y axis as on
 * real hardware; triggers run from 0 to 1. The simulator always supplies both gamepads, neutral when nothing
 * is mapped to them.
 */
public record GamepadState(
        float leftStickX,
        float leftStickY,
        float rightStickX,
        float rightStickY,
        float leftTrigger,
        float rightTrigger,
        boolean dpadUp,
        boolean dpadDown,
        boolean dpadLeft,
        boolean dpadRight,
        boolean a,
        boolean b,
        boolean x,
        boolean y,
        boolean leftBumper,
        boolean rightBumper,
        boolean leftStickButton,
        boolean rightStickButton,
        boolean start,
        boolean back,
        boolean guide) {

    /** A gamepad nobody is touching. */
    public static final GamepadState NEUTRAL = new GamepadState(
            0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, false, false, false, false, false, false, false, false,
            false, false, false);

    /** A gamepad whose sticks are held where given and which nothing else touches. */
    public static GamepadState sticks(float leftX, float leftY, float rightX, float rightY) {
        return new GamepadState(
                leftX, leftY, rightX, rightY, 0f, 0f, false, false, false, false, false, false, false, false, false, false,
                false, false, false, false, false);
    }
}
