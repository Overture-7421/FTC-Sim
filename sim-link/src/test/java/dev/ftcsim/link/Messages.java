package dev.ftcsim.link;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.List;

/** Small messages for the tests of the link, which do not care what is in them. */
final class Messages {

    private Messages() {}

    static SessionRequest request(String program) {
        return new SessionRequest(program, "none", Alliance.RED, 7L, 0.5, -0.5, 1.0, 2.0);
    }

    static SessionInfo info(String robotName) {
        return new SessionInfo(null, List.of(), new RobotInfo(robotName, 0.4, 0.4, 0.3, List.of("m"), List.of(), List.of()));
    }

    static WorldSnapshot snapshot(long step) {
        return new WorldSnapshot(step, step * 4_166_666L, List.of(new BodyState(1, 1, step, 0, 0, 0, 0, 0, 1)));
    }

    static Catalogue catalogue(String tag) {
        return new Catalogue(
                List.of(
                        new FieldChoice("none", "Bare floor " + tag, List.of(), List.of()),
                        new FieldChoice(
                                "decode", "DECODE", List.of(new StartPose("Blue goal", -1.5, 1.2, 0.5)), List.of(new HumanAction("load", "Load")))));
    }

    static SessionStatus status(long simTimeNanos) {
        return new SessionStatus(
                simTimeNanos, RobotPhase.IDLE, new TelemetrySnapshot(List.of(), "", ""), new Score(List.of()), List.of(), List.of(),
                1.0, 1.0, false);
    }
}
