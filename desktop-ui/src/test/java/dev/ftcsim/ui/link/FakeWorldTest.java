package dev.ftcsim.ui.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

class FakeWorldTest {
    private static final GamepadState FORWARD = GamepadState.sticks(0f, -1f, 0f, 0f);

    private static FakeWorld openField() {
        return new FakeWorld(-1.2, -1.4, Math.PI / 2, 0L, true);
    }

    private static void run(FakeWorld world, GamepadState pad, double seconds) {
        int steps = (int) Math.round(seconds * FakeWorld.STEPS_PER_SECOND);
        for (int i = 0; i < steps; i++) {
            world.step(pad);
        }
    }

    private static BodyState chassis(FakeWorld world) {
        return world.snapshot().bodies().get(0);
    }

    private static BodyState body(FakeWorld world, int entityId) {
        return world.snapshot().bodies().stream().filter(b -> b.entityId() == entityId).findFirst().orElseThrow();
    }

    private static FakeWorld running(String opMode) {
        FakeWorld world = openField();
        world.init(opMode);
        world.start();
        return world;
    }

    @Test
    void theChassisStaysWhereItIsUntilAnOpModeRuns() {
        FakeWorld world = openField();

        run(world, FORWARD, 1.0);

        assertEquals(-1.4f, chassis(world).y(), 1e-6f);
    }

    @Test
    void theLeftStickDrivesTheChassisAlongItsHeadingOnceTheOpModeRuns() {
        FakeWorld world = running(FakeField.TELEOP);

        run(world, FORWARD, 1.0);

        BodyState robot = chassis(world);
        assertTrue(robot.y() > -1.4f + 1.2f, "moved about 1.5 m towards +Y: " + robot.y());
        assertEquals(-1.2f, robot.x(), 1e-3f);
    }

    @Test
    void strafingAndTurningFollowTheSticks() {
        FakeWorld strafing = running(FakeField.TELEOP);
        run(strafing, GamepadState.sticks(1f, 0f, 0f, 0f), 0.5);
        assertTrue(chassis(strafing).x() > -1.2f + 0.3f, "right of a robot facing +Y is +X");

        FakeWorld turning = running(FakeField.TELEOP);
        run(turning, GamepadState.sticks(0f, 0f, 1f, 0f), 0.5);
        BodyState robot = chassis(turning);
        double yaw = 2 * Math.atan2(robot.qz(), robot.qw());
        assertTrue(yaw < Math.PI / 2 - 0.5, "the right stick turns clockwise: " + yaw);
    }

    @Test
    void theChassisStopsAtTheWall() {
        FakeWorld world = running(FakeField.TELEOP);

        run(world, FORWARD, 6.0);

        assertEquals(FakeField.HALF - 0.29, chassis(world).y(), 1e-3);
    }

    @Test
    void theChassisPushesAPieceAhead() {
        FakeWorld world = new FakeWorld(-0.9, -0.8, Math.PI / 2, 0L, true);
        world.init(FakeField.TELEOP);
        world.start();
        float before = body(world, FakeField.FIRST_PIECE_ID).y();

        run(world, FORWARD, 2.0);

        assertTrue(body(world, FakeField.FIRST_PIECE_ID).y() > before + 0.3f, "the piece in its way moved on");
    }

    @Test
    void theSnapshotListsTheChassisTheTurnstileAndThePiecesByAscendingId() {
        WorldSnapshot snapshot = openField().snapshot();

        assertEquals(1, snapshot.bodies().get(0).entityId());
        assertEquals(BodyKinds.CHASSIS, snapshot.bodies().get(0).kind());
        assertEquals(FakeField.TURNSTILE_ID, snapshot.bodies().get(1).entityId());
        assertEquals(FakeField.KIND_TURNSTILE, snapshot.bodies().get(1).kind());
        assertEquals(2 + 12, snapshot.bodies().size());
        assertEquals(0, snapshot.stepIndex());
    }

    @Test
    void simulatedTimeComesFromTheStepIndexAlone() {
        FakeWorld world = openField();

        run(world, GamepadState.NEUTRAL, 1.0);

        assertEquals(240, world.snapshot().stepIndex());
        assertEquals(1_000_000_000L, world.snapshot().simTimeNanos());
    }

    @Test
    void theTurnstileTurnsWithSimulatedTimeWhileAnOpModeRuns() {
        FakeWorld idle = openField();
        run(idle, GamepadState.NEUTRAL, 1.0);
        assertEquals(0f, body(idle, FakeField.TURNSTILE_ID).qz(), "nothing moves while no OpMode runs");

        FakeWorld running = running(FakeField.TELEOP);
        BodyState before = body(running, FakeField.TURNSTILE_ID);
        run(running, GamepadState.NEUTRAL, 1.0);

        BodyState after = body(running, FakeField.TURNSTILE_ID);
        assertTrue(Math.abs(after.qz() - before.qz()) > 0.1f);
    }

    @Test
    void theDriverStationRulesDecideWhichCommandsCount() {
        FakeWorld world = openField();

        world.start();
        assertEquals(RobotPhase.IDLE, world.phase(), "Start needs Init first");
        world.init("no such OpMode");
        assertEquals(RobotPhase.IDLE, world.phase());

        world.init(FakeField.TELEOP);
        assertEquals(RobotPhase.INIT, world.phase());
        world.init(FakeField.SQUARE);
        assertEquals(RobotPhase.INIT, world.phase(), "another Init while one is initialised does nothing");
        world.start();
        assertEquals(RobotPhase.RUNNING, world.phase());
        world.stop();
        assertEquals(RobotPhase.IDLE, world.phase());
    }

    @Test
    void aWorldIsAtRestUntilAnOpModeRunsOrAPieceIsKnockedAbout() {
        FakeWorld world = openField();
        assertTrue(world.atRest());

        world.init(FakeField.TELEOP);
        assertTrue(world.atRest(), "initialised is not yet running");
        world.start();
        assertFalse(world.atRest());
        run(world, FORWARD, 0.5);
        world.stop();
        assertFalse(world.atRest(), "the chassis is still rolling");
        run(world, GamepadState.NEUTRAL, 3.0);
        assertTrue(world.atRest(), "the chassis has slowed to a halt");

        world.humanAction(FakeField.ACTION_SCATTER);
        assertFalse(world.atRest());
        run(world, GamepadState.NEUTRAL, 30.0);
        assertTrue(world.atRest(), "friction stops the pieces");
    }

    @Test
    void theDriveSquareOpModeDrivesItselfAndTurnsBackToWhereItFaced() {
        FakeWorld world = new FakeWorld(-0.3, -1.2, 0.0, 0L, true);
        world.init(FakeField.SQUARE);
        world.start();

        run(world, GamepadState.NEUTRAL, 10.0);

        assertEquals(RobotPhase.RUNNING, world.phase());
        BodyState robot = chassis(world);
        double yaw = 2 * Math.atan2(robot.qz(), robot.qw());
        assertEquals(0.0, Math.IEEEremainder(yaw, 2 * Math.PI), 0.15, "four quarter turns");
        assertTrue(world.status(1, 1, false).telemetry().lines().get(2).contains("0.00 m/s"));
    }

    @Test
    void anOpModeThatCrashesReportsItsErrorAndLeavesTheRobotIdle() {
        FakeWorld world = running(FakeField.CRASH);

        run(world, GamepadState.NEUTRAL, 3.2);

        SessionStatus status = world.status(1, 1, false);
        assertEquals(RobotPhase.IDLE, status.phase());
        assertTrue(status.telemetry().error().contains("IllegalStateException"));
    }

    @Test
    void theTelemetryOpModeRaisesAWarningAndAProblemAsTimePasses() {
        FakeWorld world = running(FakeField.TELEMETRY);
        assertTrue(world.status(1, 1, false).problems().isEmpty());

        run(world, GamepadState.NEUTRAL, 2.5);

        SessionStatus status = world.status(1, 1, false);
        assertFalse(status.telemetry().warnings().isEmpty());
        assertEquals(1, status.problems().size());
        assertTrue(status.telemetry().lines().size() > 40);
    }

    @Test
    void humanActionsFeedAPieceAndScatterThem() {
        FakeWorld world = openField();
        int bodies = world.snapshot().bodies().size();

        world.humanAction(FakeField.ACTION_FEED);
        assertEquals(bodies + 1, world.snapshot().bodies().size());

        float before = body(world, FakeField.FIRST_PIECE_ID).x();
        world.humanAction(FakeField.ACTION_SCATTER);
        run(world, GamepadState.NEUTRAL, 0.2);
        assertTrue(Math.abs(body(world, FakeField.FIRST_PIECE_ID).x() - before) > 0.05f);
    }

    @Test
    void theScoreCountsPiecesThatAreInsideAGoal() {
        FakeWorld world = openField();
        assertEquals(0, world.score().total());

        world.spawn(FakeField.KIND_GREEN, FakeField.GOAL_X, FakeField.GOAL_Y);
        world.spawn(FakeField.KIND_PURPLE, -FakeField.GOAL_X, FakeField.GOAL_Y);

        assertEquals(3 + 5, world.score().total());
        assertEquals(2, world.score().lines().size());
    }

    @Test
    void theHardwareReadoutsFollowTheWheels() {
        FakeWorld world = running(FakeField.TELEOP);

        run(world, FORWARD, 1.0);

        SessionStatus status = world.status(1, 1, false);
        assertNotNull(status.hardware());
        assertEquals("frontLeft", status.hardware().get(0).name());
        assertFalse(status.hardware().get(0).value().startsWith("0 ticks/s"));
    }

    private static final MatchTimes SHORT = MatchTimes.ofMillis(500, 250, 750);

    private static FakeWorld matchWorld() {
        return new FakeWorld(-1.2, -1.4, Math.PI / 2, 0L, true, SHORT);
    }

    private static MatchStatus match(FakeWorld world) {
        return world.status(1, 1, false).match();
    }

    @Test
    void aWorldThatNobodyArmedIsInFreePractice() {
        FakeWorld world = matchWorld();

        assertEquals(MatchStatus.NONE, match(world));
        run(world, GamepadState.NEUTRAL, 1.0);
        assertEquals(MatchStatus.NONE, match(world), "free practice never leaves NONE");
    }

    @Test
    void armingInitialisesTheAutonomousOpModeAndStartingRunsIt() {
        FakeWorld world = matchWorld();

        assertNull(world.armMatch(FakeField.SQUARE, FakeField.TELEOP));

        assertEquals(MatchState.ARMED, world.matchState());
        assertEquals(RobotPhase.INIT, world.phase());
        assertEquals(new MatchStatus(MatchState.ARMED, 0, 0), match(world));
        assertTrue(world.status(1, 1, false).telemetry().lines().get(0).contains(FakeField.SQUARE));

        assertNull(world.startMatch());
        assertEquals(MatchState.AUTONOMOUS, world.matchState());
        assertEquals(RobotPhase.RUNNING, world.phase());
    }

    @Test
    void thePeriodsFollowEachOtherInSimulatedTimeAndReportTheirOwnLengths() {
        FakeWorld world = matchWorld();
        world.armMatch(FakeField.SQUARE, FakeField.TELEOP);
        world.startMatch();

        run(world, GamepadState.NEUTRAL, 0.25);
        assertEquals(MatchState.AUTONOMOUS, match(world).state());
        assertEquals(SHORT.autonomousNanos(), match(world).phaseDurationNanos());
        assertEquals(250_000_000L, match(world).phaseElapsedNanos(), 5_000_000L);

        run(world, GamepadState.NEUTRAL, 0.30);
        assertEquals(MatchState.TRANSITION, match(world).state());
        assertEquals(SHORT.transitionNanos(), match(world).phaseDurationNanos());
        assertEquals(RobotPhase.INIT, world.phase(), "the driver-controlled OpMode is initialised in the transition");
        assertTrue(world.status(1, 1, false).telemetry().lines().get(0).contains(FakeField.TELEOP));

        run(world, GamepadState.NEUTRAL, 0.25);
        assertEquals(MatchState.TELEOP, match(world).state());
        assertEquals(RobotPhase.RUNNING, world.phase());

        run(world, GamepadState.NEUTRAL, 0.80);
        assertEquals(MatchState.ENDED, match(world).state());
        assertEquals(new MatchStatus(MatchState.ENDED, 0, 0), match(world));
        assertEquals(RobotPhase.IDLE, world.phase());
    }

    @Test
    void aMatchWithoutAnAutonomousPeriodStartsInTheDriverControlledOne() {
        FakeWorld world = matchWorld();

        assertNull(world.armMatch("", FakeField.TELEOP));
        assertEquals(RobotPhase.INIT, world.phase());
        assertNull(world.startMatch());

        assertEquals(MatchState.TELEOP, world.matchState());
        assertEquals(RobotPhase.RUNNING, world.phase());
    }

    @Test
    void theMatchClockIsTheStepCountAndNothingElse() {
        FakeWorld a = matchWorld();
        FakeWorld b = matchWorld();
        a.armMatch(FakeField.SQUARE, FakeField.TELEOP);
        b.armMatch(FakeField.SQUARE, FakeField.TELEOP);
        a.startMatch();
        b.startMatch();

        run(a, GamepadState.NEUTRAL, 1.0);
        for (int i = 0; i < 240; i++) {
            b.step(i % 2 == 0 ? FORWARD : GamepadState.NEUTRAL);
        }

        assertEquals(match(a), match(b), "the gamepads do not decide when a period ends");
    }

    @Test
    void stoppingGivesUpAnArmedMatchAndEndsOneUnderWay() {
        FakeWorld armed = matchWorld();
        armed.armMatch(FakeField.SQUARE, FakeField.TELEOP);
        armed.stop();
        assertEquals(MatchState.NONE, armed.matchState());
        assertEquals(RobotPhase.IDLE, armed.phase());
        assertNull(armed.armMatch(FakeField.SQUARE, FakeField.TELEOP), "a match that was given up may be armed again");

        FakeWorld playing = matchWorld();
        playing.armMatch(FakeField.SQUARE, FakeField.TELEOP);
        playing.startMatch();
        run(playing, GamepadState.NEUTRAL, 0.1);
        playing.stop();
        assertEquals(MatchState.ENDED, playing.matchState());
        assertEquals(RobotPhase.IDLE, playing.phase());
        run(playing, GamepadState.NEUTRAL, 2.0);
        assertEquals(MatchState.ENDED, playing.matchState(), "an ended match stays ended");
    }

    @Test
    void theCommandsThatCannotBeDoneSayWhyAndChangeNothing() {
        FakeWorld world = matchWorld();
        world.init(FakeField.TELEOP);
        assertTrue(world.armMatch(FakeField.SQUARE, FakeField.TELEOP).contains("OpMode is initialised or running"));
        assertEquals(MatchState.NONE, world.matchState());
        world.stop();

        assertTrue(world.armMatch("no such opmode", FakeField.TELEOP).contains("no OpMode called no such opmode"));
        assertTrue(world.armMatch(FakeField.SQUARE, "").contains("no OpMode called"));
        assertEquals(MatchState.NONE, world.matchState());
        assertEquals(RobotPhase.IDLE, world.phase());
        assertTrue(world.startMatch().contains("no armed match"));

        world.armMatch(FakeField.SQUARE, FakeField.TELEOP);
        assertTrue(world.armMatch(FakeField.SQUARE, FakeField.TELEOP).contains("already armed or played"));
        assertTrue(world.init(FakeField.TELEOP).contains("match is armed or under way"));
        assertTrue(world.start().contains("match is armed or under way"));
        assertEquals(MatchState.ARMED, world.matchState(), "the refusals left the armed match alone");
    }

    @Test
    void aPlayedMatchNeedsANewWorldButPracticeMayFollow() {
        FakeWorld world = matchWorld();
        world.armMatch("", FakeField.TELEOP);
        world.startMatch();
        run(world, GamepadState.NEUTRAL, 1.0);
        assertEquals(MatchState.ENDED, world.matchState());

        assertTrue(world.armMatch("", FakeField.TELEOP).contains("Reset the session"));
        assertNull(world.init(FakeField.TELEOP), "free practice is possible again");
        assertNull(world.start());
        assertEquals(RobotPhase.RUNNING, world.phase());
    }

    @Test
    void anUnknownHumanActionIsRefusedAndABareFloorHasNone() {
        FakeWorld world = matchWorld();
        assertNull(world.humanAction(FakeField.ACTION_FEED));
        assertTrue(world.humanAction("juggle").contains("no human action called juggle"));

        FakeWorld bare = new FakeWorld(0, 0, 0, 0L, false);
        assertTrue(bare.humanAction(FakeField.ACTION_FEED).contains("bare floor"));
    }
}
