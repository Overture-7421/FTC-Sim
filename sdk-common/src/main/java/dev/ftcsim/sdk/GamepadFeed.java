package dev.ftcsim.sdk;

import com.qualcomm.robotcore.hardware.Gamepad;
import dev.ftcsim.contracts.robot.GamepadState;
import org.firstinspires.ftc.robotcore.internal.ui.GamepadUser;

/**
 * The gamepad plumbing of the robot controller's event loop. The OpMode's own {@code Gamepad} instances live as
 * long as the session, because they hold queued rumble and LED effects, and the "latest data" instances are
 * replaced whenever the driver station would have sent a new packet, which is how the SDK notices a change.
 */
final class GamepadFeed {
    private final Gamepad[] opModeGamepads = {new Gamepad(), new Gamepad()};
    private final GamepadState[] reported = new GamepadState[2];
    private final Gamepad[] latest = {new Gamepad(), new Gamepad()};

    Gamepad[] opModeGamepads() {
        return opModeGamepads;
    }

    Gamepad latest(GamepadUser user) {
        return latest[index(user)];
    }

    /** Accepts the current state of both gamepads; a gamepad whose state did not change keeps its data instance. */
    void update(GamepadState first, GamepadState second) {
        accept(GamepadUser.ONE, first);
        accept(GamepadUser.TWO, second);
    }

    private void accept(GamepadUser user, GamepadState state) {
        int slot = index(user);
        if (!state.equals(reported[slot])) {
            reported[slot] = state;
            latest[slot] = packet(user, state);
        }
    }

    private static int index(GamepadUser user) {
        return user == GamepadUser.ONE ? 0 : 1;
    }

    private static Gamepad packet(GamepadUser user, GamepadState state) {
        Gamepad gamepad = new Gamepad();
        gamepad.left_stick_x = state.leftStickX();
        gamepad.left_stick_y = state.leftStickY();
        gamepad.right_stick_x = state.rightStickX();
        gamepad.right_stick_y = state.rightStickY();
        gamepad.left_trigger = state.leftTrigger();
        gamepad.right_trigger = state.rightTrigger();
        gamepad.dpad_up = state.dpadUp();
        gamepad.dpad_down = state.dpadDown();
        gamepad.dpad_left = state.dpadLeft();
        gamepad.dpad_right = state.dpadRight();
        gamepad.a = state.a();
        gamepad.b = state.b();
        gamepad.x = state.x();
        gamepad.y = state.y();
        gamepad.left_bumper = state.leftBumper();
        gamepad.right_bumper = state.rightBumper();
        gamepad.left_stick_button = state.leftStickButton();
        gamepad.right_stick_button = state.rightStickButton();
        gamepad.start = state.start();
        gamepad.back = state.back();
        gamepad.guide = state.guide();
        gamepad.setUser(user);
        gamepad.refreshTimestamp();
        return gamepad;
    }
}
