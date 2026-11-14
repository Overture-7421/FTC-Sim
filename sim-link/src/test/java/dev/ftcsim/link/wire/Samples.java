package dev.ftcsim.link.wire;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.contracts.session.Readout;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The messages of the port as the tests of the wire format build them. */
final class Samples {

    /** A quiet NaN with a payload, which only a codec that keeps the raw bits brings back. */
    static final float PAYLOAD_NAN = Float.intBitsToFloat(0x7FC01234);

    private Samples() {}

    static SessionRequest request() {
        return new SessionRequest(
                "hornet-2026.json", "C:\\Users\\Team 1234\\programs\\hornet", "decode", Alliance.BLUE, -42L, 1.25, -3.5, Math.PI / 3, 0.1);
    }

    static GamepadState gamepad() {
        return new GamepadState(
                -0.3f, 1f, PAYLOAD_NAN, -0.0f, 0.25f, 1f, true, false, true, false, true, false, true, false, true, false, true,
                false, true, false, true);
    }

    static GamepadState everyButtonDown() {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true);
    }

    static FieldDescription field() {
        FieldPart wall = new FieldPart(new Shape.Box(0.0125, 1.8, 0.15), Pose3.at(0.1, 0.2, 0.3), 0x336699);
        FieldPart ball = new FieldPart(new Shape.Sphere(0.0635), Pose3.IDENTITY, 0x7B2CBF);
        FieldPart post = new FieldPart(new Shape.Cylinder(0.02, 0.4), Pose3.turned(0, 0, 0.4, 0, 1, 0, 0.1), 0xFFFFFF);
        return new FieldDescription(
                "decode",
                "DECODE ✓ 2025–26",
                List.of(new Scenery("north wall", wall, Pose3.at(0, 1.8, 0.15)), new Scenery("goal", post, Pose3.turned(1, 1, 0, 0, 0, 1, 2.5))),
                List.of(new BodyKind(100, "purple ball", List.of(ball)), new BodyKind(200, "ramp gate", List.of(wall, post))),
                List.of(new FieldTag(20, 0.1651, -1, Pose3.at(-1.5, 1.4, 0.75)), new FieldTag(24, 0.1651, 1000, Pose3.IDENTITY)),
                List.of(new StartPose("red far", 1.2, -1.5, Math.PI), new StartPose("blue near", -0.3, 0.4, 0)),
                List.of(new HumanAction("feed", "Load a piece"), new HumanAction("clear", "Clear the ramp")));
    }

    static SessionInfo info(boolean withField) {
        return new SessionInfo(
                withField ? field() : null,
                List.of(
                        new OpModeDescriptor("Mecanum Drive", "Drive", OpModeKind.TELEOP, "dev.ftcsim.scenarios.opmodes.MecanumDriveTeleOp"),
                        new OpModeDescriptor("Two Ball Auto", "", OpModeKind.AUTONOMOUS, "org.team.TwoBallAuto")),
                new RobotInfo("Hornet 2026", 0.4572, 0.4572, 0.3048, List.of("frontLeft", "frontRight"), List.of("hood"), List.of("odo")));
    }

    static WorldSnapshot snapshot() {
        List<BodyState> bodies = new ArrayList<>();
        bodies.add(new BodyState(1, 1, -0.0f, Float.MIN_VALUE, Float.POSITIVE_INFINITY, PAYLOAD_NAN, 0.1f, Float.MAX_VALUE, -1.5f));
        for (int i = 0; i < 40; i++) {
            bodies.add(new BodyState(1000 + 3 * i, 100 + i % 3, 0.01f * i, -0.02f * i, 0.0635f, 0f, 0f, 0.7071068f, 0.7071068f));
        }
        return new WorldSnapshot(123_456_789L, 514_403_287_500L, bodies);
    }

    static SessionStatus status() {
        return status(new MatchStatus(MatchState.TRANSITION, 3_000_000_000L, 8_000_000_000L));
    }

    static SessionStatus status(MatchStatus match) {
        return new SessionStatus(
                5_000_000_000L,
                RobotPhase.RUNNING,
                new TelemetrySnapshot(List.of("x: 1.5", "Heading ° 12", ""), "low battery", "java.lang.IllegalStateException\n\tat Foo.bar"),
                new Score(List.of(new Score.Line("classified", 3, 9), new Score.Line("pattern", 2, 4))),
                List.of("hub 173 refused a command", "port 3 had no motor"),
                List.of(new Readout("frontLeft", "812 ticks/s  1.2 A"), new Readout("hood", "1500 us")),
                2.0,
                1.75,
                true,
                match);
    }

    static Catalogue catalogue() {
        return new Catalogue(
                List.of(
                        new FieldChoice("none", "Bare floor", List.of(), List.of()),
                        new FieldChoice(
                                "decode",
                                "DECODE ✓ 2025–26",
                                List.of(new StartPose("red far", 1.2, -1.5, Math.PI), new StartPose("blue near", -0.3, 0.4, 0)),
                                List.of(new HumanAction("feed", "Load a piece"), new HumanAction("clear", "Clear the ramp")))),
                List.of("hornet-2026.json", "ünïcode.json"));
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
