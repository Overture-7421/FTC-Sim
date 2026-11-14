package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.contracts.robot.GamepadState;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class GamepadHostTest {
    private static final int BUTTONS = 15;

    @Test
    void bothGamepadsAlwaysExistAndAreNeutralUntilTheSimulatorSaysOtherwise() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Gamepad Echo");

            var snapshot = world.awaitTelemetry("both gamepads in Init", s -> s.lines().size() == 3);

            assertEquals(echo(GamepadState.NEUTRAL, GamepadState.NEUTRAL), snapshot.lines());
        }
    }

    @Test
    void everyFieldOfAGamepadStateReachesTheMatchingFieldOfGamepad1AndGamepad2() throws Exception {
        GamepadState first = new GamepadState(
                0.25f, -0.5f, 0.75f, -1f, 0.5f, 1f,
                true, false, true, false,
                true, false, true, false,
                true, false, true, false,
                true, false, true);
        GamepadState second = new GamepadState(
                -0.25f, 0.5f, -0.75f, 1f, 1f, 0.5f,
                false, true, false, true,
                false, true, false, true,
                false, true, false, true,
                false, true, false);
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.hold(first, second);
            world.host().init("Gamepad Echo");
            world.host().start();

            world.awaitTelemetry("both gamepads in Run", s -> s.lines().equals(echo(first, second)));
        }
    }

    @Test
    void eachButtonOfEachGamepadReachesTheOpModeOnItsOwn() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Gamepad Echo");
            world.host().start();

            for (int button = 0; button < BUTTONS; button++) {
                GamepadState first = onlyButton(button);
                GamepadState second = onlyButton((button + 1) % BUTTONS);
                world.hold(first, second);
                world.awaitTelemetry("only button " + button + " pressed", s -> s.lines().equals(echo(first, second)));
            }
        }
    }

    /** The buttons in the order of the record: the four d-pad directions, A B X Y, the bumpers, the stick buttons, Start, Back, Guide. */
    private static GamepadState onlyButton(int index) {
        boolean[] b = new boolean[BUTTONS];
        b[index] = true;
        return new GamepadState(0, 0, 0, 0, 0, 0, b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14]);
    }

    private static List<String> echo(GamepadState first, GamepadState second) {
        return List.of("both gamepads exist", line(1, first), line(2, second));
    }

    private static String line(int number, GamepadState s) {
        return String.format(
                Locale.ROOT,
                "pad%d lx=%.2f ly=%.2f rx=%.2f ry=%.2f lt=%.2f rt=%.2f up=%b down=%b left=%b right=%b"
                        + " a=%b b=%b x=%b y=%b lb=%b rb=%b ls=%b rs=%b start=%b back=%b guide=%b",
                number,
                s.leftStickX(),
                s.leftStickY(),
                s.rightStickX(),
                s.rightStickY(),
                s.leftTrigger(),
                s.rightTrigger(),
                s.dpadUp(),
                s.dpadDown(),
                s.dpadLeft(),
                s.dpadRight(),
                s.a(),
                s.b(),
                s.x(),
                s.y(),
                s.leftBumper(),
                s.rightBumper(),
                s.leftStickButton(),
                s.rightStickButton(),
                s.start(),
                s.back(),
                s.guide());
    }
}
