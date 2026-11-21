package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.game.biobuzz.BiobuzzIds;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(180)
class BiobuzzFlowerAutoSessionTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void shootsAllPreloadsAndRetrievesPollenFromTheFlower(int seed) throws IOException {
        var field = new BiobuzzField();
        var start = field.description().startPoses().stream().filter(pose -> pose.name().equals("RED wall, centre")).findFirst().orElseThrow();
        try (var handle = TestSessions.open(BiobuzzTestRobot.spec(), TestSessions.program(), field,
                new FieldSetup(seed, Alliance.RED), start.x(), start.y(), start.headingRadians())) {
            var session = handle.session;
            session.init("BIOBUZZ Flower Demo");
            session.runFor(1_000_000_000L);
            session.start();
            Set<Integer> shots = new HashSet<>();
            int bottom = BiobuzzIds.POLLEN_FIRST + 12;
            double restingHeight = session.world().bodyPose(bottom).z();
            double lift = 0;
            String stage = "";
            StringBuilder trace = new StringBuilder();
            for (int tick = 0; tick < 3000; tick++) {
                session.runFor(10_000_000L);
                for (int id = BiobuzzIds.POLLEN_FIRST + 28; id < BiobuzzIds.POLLEN_FIRST + 32; id++) {
                    if (session.world().hasPiece(id) && session.world().bodyPose(id).vz() > 1) {
                        shots.add(id);
                    }
                }
                if (session.telemetry().lines().stream().noneMatch(line -> line.startsWith("Stage : "))) {
                    continue;
                }
                String next = SessionTelemetry.text(session, "Stage");
                if (!next.equals(stage)) {
                    trace.append(next).append(" at ").append(SessionTelemetry.text(session, "Pose"))
                            .append(" ball ").append(session.world().hasPiece(bottom) ? session.world().bodyPose(bottom).position() : "collected")
                            .append("\n");
                }
                stage = next;
                if ((stage.equals("NUDGE") || stage.equals("APPROACH")) && session.world().hasPiece(bottom)) {
                    BodyPose ball = session.world().bodyPose(bottom);
                    lift = Math.max(lift, ball.z() - restingHeight);
                }
                if (stage.equals("FAILED") || stage.equals("COMPLETE")) {
                    break;
                }
            }
            assertEquals("COMPLETE", stage, session.telemetry().toString() + trace);
            assertEquals(4, shots.size());
            assertTrue(lift > 0.006, "bottom POLLEN rose in the retrieval opening: " + lift + "\n" + trace);
            int collected = 0;
            for (int id = bottom; id < bottom + 4; id++) {
                if (session.world().hasPiece(id)) {
                    BodyPose loose = session.world().bodyPose(id);
                    assertTrue(Math.hypot(loose.x() - 23.39 * 0.0254, loose.y() + 69.52 * 0.0254) > 0.062,
                            "staged POLLEN " + id + " still in FLOWER at " + loose.position());
                } else {
                    collected++;
                }
            }
            assertTrue(collected > 0, "at least one of the four released POLLEN reached the intake");
        }
    }
}
