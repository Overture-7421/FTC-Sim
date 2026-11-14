package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.contracts.robot.GamepadState;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionHostTest {
    private static final GamepadState LEFT_STICK_PUSHED = new GamepadState(
            0.9f, 0, 0, 0, 0, 0, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);

    @Test
    void staticStateOfTheRobotProgramSurvivesFromOneOpModeToTheNext() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.host().init("Session Writer");
            world.host().start();
            world.awaitTelemetry("the writer to run", s -> s.lines().contains("only in the writer : yes"));
            world.host().stop();

            world.host().init("Session Reader");
            world.host().start();

            world.awaitTelemetry(
                    "the reader to see what the writer left", s -> s.lines().contains("session : written by the Session Writer"));
        }
    }

    @Test
    void aSecondOpModeStartsWithNeitherTheTelemetryNorTheGamepadStateOfTheFirst() throws Exception {
        try (HostedRobotWorld world = HostedRobotWorld.launch(new DeclineNativesHooks())) {
            world.hold(LEFT_STICK_PUSHED, GamepadState.NEUTRAL);
            world.host().init("Session Writer");
            world.host().start();
            world.awaitTelemetry(
                    "the writer to see the pushed stick",
                    s -> s.lines().contains("only in the writer : yes")
                            && s.lines().stream().anyMatch(line -> line.startsWith("pad1 lx=0.90 ")));
            world.host().stop();

            world.hold(GamepadState.NEUTRAL, GamepadState.NEUTRAL);
            world.host().init("Session Reader");
            world.host().start();

            var snapshot = world.awaitTelemetry("the reader to run", s -> !s.lines().isEmpty());
            assertEquals(2, snapshot.lines().size(), snapshot.lines().toString());
            assertEquals("session : written by the Session Writer", snapshot.lines().get(0));
            assertEquals(neutralPadLine(), snapshot.lines().get(1));
            world.awaitTelemetry("a second reader update", s -> s.lines().equals(List.of(snapshot.lines().get(0), neutralPadLine())));
        }
    }

    private static String neutralPadLine() {
        return "pad1 lx=0.00 ly=0.00 rx=0.00 ry=0.00 lt=0.00 rt=0.00 up=false down=false left=false right=false"
                + " a=false b=false x=false y=false lb=false rb=false ls=false rs=false start=false back=false guide=false";
    }
}
