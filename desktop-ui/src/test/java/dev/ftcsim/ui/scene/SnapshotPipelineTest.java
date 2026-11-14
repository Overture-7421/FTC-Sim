package dev.ftcsim.ui.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotPipelineTest {
    private static final long STEP = 4_000_000L;

    private static BodyState body(int id, float x, float qz, float qw) {
        return new BodyState(id, 1, x, 0f, 0f, 0f, 0f, qz, qw);
    }

    private static WorldSnapshot snapshot(long step, BodyState... bodies) {
        return new WorldSnapshot(step, step * STEP, List.of(bodies));
    }

    private static SnapshotMailbox.Latest pair(long previousStep, long previousNanos, long currentStep, long currentNanos) {
        return new SnapshotMailbox.Latest(
                snapshot(previousStep, body(1, 0f, 0f, 1f)), snapshot(currentStep, body(1, 1f, 0f, 1f)), previousNanos, currentNanos);
    }

    @Test
    void mailboxKeepsOnlyTheTwoNewestSnapshots() {
        SnapshotMailbox mailbox = new SnapshotMailbox();
        assertNull(mailbox.latest());

        WorldSnapshot first = snapshot(1, body(1, 0f, 0f, 1f));
        WorldSnapshot second = snapshot(2, body(1, 1f, 0f, 1f));
        WorldSnapshot third = snapshot(3, body(1, 2f, 0f, 1f));
        mailbox.publish(first, 100);
        assertSame(first, mailbox.latest().previous());
        assertSame(first, mailbox.latest().current());

        mailbox.publish(second, 200);
        mailbox.publish(third, 300);

        SnapshotMailbox.Latest latest = mailbox.latest();
        assertSame(second, latest.previous());
        assertSame(third, latest.current());
        assertEquals(200, latest.previousNanos());
        assertEquals(300, latest.currentNanos());
    }

    @Test
    void aRepublishedSnapshotOfTheSameStepIsIgnored() {
        SnapshotMailbox mailbox = new SnapshotMailbox();
        WorldSnapshot first = snapshot(1, body(1, 0f, 0f, 1f));
        WorldSnapshot second = snapshot(2, body(1, 1f, 0f, 1f));
        mailbox.publish(first, 100);
        mailbox.publish(second, 200);

        mailbox.publish(snapshot(2, body(1, 1f, 0f, 1f)), 300);

        assertSame(first, mailbox.latest().previous());
        assertSame(second, mailbox.latest().current());
        assertEquals(200, mailbox.latest().currentNanos());
    }

    @Test
    void publishingTellsWhetherTheWorldChangedAndAWorldAtRestIsNotNews() {
        SnapshotMailbox mailbox = new SnapshotMailbox();

        assertTrue(mailbox.publish(snapshot(1, body(1, 0f, 0f, 1f)), 100), "the first snapshot is always news");
        assertFalse(mailbox.publish(snapshot(2, body(1, 0f, 0f, 1f)), 200), "the same bodies at the same poses");
        assertTrue(mailbox.publish(snapshot(3, body(1, 1f, 0f, 1f)), 300), "the body moved");
        assertFalse(mailbox.publish(snapshot(3, body(1, 5f, 0f, 1f)), 400), "the same step again is ignored");
        assertEquals(300, mailbox.latest().currentNanos());
        assertTrue(mailbox.publish(snapshot(4, body(1, 1f, 0f, 1f), body(2, 0f, 0f, 1f)), 500), "a body appeared");
    }

    @Test
    void clearForgetsTheOldSession() {
        SnapshotMailbox mailbox = new SnapshotMailbox();
        mailbox.publish(snapshot(1, body(1, 0f, 0f, 1f)), 100);

        mailbox.clear();

        assertNull(mailbox.latest());
    }

    @Test
    void theViewMovesFromThePreviousToTheCurrentSnapshotOverTheirArrivalSpacing() {
        SnapshotMailbox.Latest latest = pair(1, 1000, 2, 2000);

        assertEquals(0.0, PoseInterpolator.alpha(latest, 2000));
        assertEquals(0.5, PoseInterpolator.alpha(latest, 2500));
        assertEquals(1.0, PoseInterpolator.alpha(latest, 3000));
        assertEquals(1.0, PoseInterpolator.alpha(latest, 9000), "holds the last pose when the producer stalls");
        assertEquals(0.0, PoseInterpolator.alpha(latest, 10), "never runs backwards");
    }

    @Test
    void aPairThatIsNotConsecutiveInSimulatedTimeIsShownAsItIs() {
        assertEquals(1.0, PoseInterpolator.alpha(pair(5, 1000, 5, 2000), 2000), "the same step twice");
        assertEquals(1.0, PoseInterpolator.alpha(pair(500, 1000, 3, 2000), 2000), "a restart goes back in steps");
    }

    @Test
    void aLongPauseBetweenTwoSnapshotsIsNotPlayedBackAsSlowMotion() {
        // One step of simulated time, ten seconds of wall time: the picture catches up within a few steps' worth.
        SnapshotMailbox.Latest afterPause = pair(1, 0, 2, 10_000_000_000L);

        double alpha = PoseInterpolator.alpha(afterPause, 10_000_000_000L + 8 * STEP);

        assertEquals(1.0, alpha);
    }

    @Test
    void interpolationBlendsPositionAndKeepsQuaternionsNormalizedOnTheShortArc() {
        float half = (float) Math.sin(Math.toRadians(45) / 2);
        float halfW = (float) Math.cos(Math.toRadians(45) / 2);
        // The second pose uses the negated quaternion, the same rotation reached the long way round.
        SnapshotMailbox mailbox = new SnapshotMailbox();
        mailbox.publish(snapshot(1, body(1, 0f, 0f, 1f)), 0);
        mailbox.publish(snapshot(2, body(1, 2f, -half, -halfW)), 1000);

        PoseInterpolator.Poses poses = new PoseInterpolator.Poses();
        PoseInterpolator.interpolate(mailbox.latest(), 0.5, poses);

        assertEquals(1, poses.count);
        assertEquals(1f, poses.values[0], 1e-6f);
        float qz = poses.values[5];
        float qw = poses.values[6];
        assertEquals(1.0, Math.sqrt(qz * qz + qw * qw), 1e-5);
        assertEquals(Math.toRadians(22.5), 2 * Math.atan2(qz, qw), 0.01, "halfway along the short arc");
    }

    @Test
    void interpolationMatchesBodiesByIdWhenEntitiesAppearOrDisappear() {
        SnapshotMailbox mailbox = new SnapshotMailbox();
        mailbox.publish(snapshot(1, body(1, 0f, 0f, 1f), body(2, 10f, 0f, 1f)), 0);
        mailbox.publish(snapshot(2, body(2, 20f, 0f, 1f), body(3, 5f, 0f, 1f)), 1000);

        PoseInterpolator.Poses poses = new PoseInterpolator.Poses();
        PoseInterpolator.interpolate(mailbox.latest(), 0.5, poses);

        assertEquals(2, poses.count);
        assertEquals(2, poses.entityIds[0]);
        assertEquals(15f, poses.values[0], 1e-6f);
        assertEquals(3, poses.entityIds[1]);
        assertEquals(5f, poses.values[PoseInterpolator.POSE_STRIDE], 1e-6f, "a new entity has nothing to blend from");
    }

    @Test
    void theRobotIsFoundByKindAndItsHeadingReadFromItsQuaternion() {
        float half = (float) Math.sin(Math.toRadians(30) / 2);
        float halfW = (float) Math.cos(Math.toRadians(30) / 2);
        SnapshotMailbox mailbox = new SnapshotMailbox();
        mailbox.publish(snapshot(1, new BodyState(7, 100, 0f, 0f, 0f, 0f, 0f, 0f, 1f), new BodyState(9, 1, 3f, 4f, 0f, 0f, 0f, half, halfW)), 0);

        PoseInterpolator.Poses poses = new PoseInterpolator.Poses();
        PoseInterpolator.interpolate(mailbox.latest(), 1.0, poses);

        assertEquals(1, poses.indexOfKind(BodyKinds.CHASSIS));
        assertEquals(-1, poses.indexOfKind(55));
        assertEquals(Math.toRadians(30), poses.yaw(1), 1e-5);
    }
}
