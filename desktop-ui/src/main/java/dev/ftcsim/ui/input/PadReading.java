package dev.ftcsim.ui.input;

/**
 * What one connected controller reported at one poll, in GLFW's gamepad layout when {@code mapped} (six axes, fifteen
 * buttons) and with no readings otherwise.
 *
 * @param id   the joystick slot GLFW gave it, which changes when it is plugged in again
 * @param guid what GLFW calls the model, which does not
 */
public record PadReading(int id, String name, String guid, boolean mapped, float[] axes, boolean[] buttons) {

    /** What to call the controller on screen. */
    public String displayName() {
        return name == null || name.isBlank() ? "Controller " + id : name;
    }
}
