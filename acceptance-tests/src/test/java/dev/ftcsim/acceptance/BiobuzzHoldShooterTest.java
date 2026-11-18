package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.scenarios.opmodes.BiobuzzHive.Alliance;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive.Tilt;
import dev.ftcsim.scenarios.opmodes.BiobuzzHoldShooter;
import dev.ftcsim.scenarios.opmodes.BiobuzzHoldShooter.Command;
import dev.ftcsim.scenarios.opmodes.BiobuzzHoldShooter.State;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Pose;
import dev.ftcsim.scenarios.opmodes.BiobuzzShotSolver;
import org.junit.jupiter.api.Test;

/** The hold-to-shoot sequence on its own: when it spins up, opens the stopper and feeds, and that none of it outlasts the button. */
class BiobuzzHoldShooterTest {
    private static final double DT = 0.02;
    private static final Pose POSE = new Pose(1.4, -1.0, Math.atan2(-0.3236 + 1.0, 0.4 - 1.4));

    private final BiobuzzHoldShooter shooter = new BiobuzzHoldShooter(Alliance.RED);
    private double now;
    private Tilt tilt = Tilt.AUDIENCE_UP;
    private Pose pose = POSE;
    private double flywheelTicks;
    private double speed;
    private boolean confirmed = true;

    /** One loop with the turret exactly on the bearing, and the flywheels at {@code flywheelTicks}. */
    private Command step(boolean held) {
        double bearing = BiobuzzShotSolver.bearingDegrees(pose, Alliance.RED, tilt);
        Command command = shooter.update(now, held, pose, confirmed, tilt, bearing, flywheelTicks, speed, 0.0);
        now += DT;
        return command;
    }

    /** Holds the button, with the flywheels at the speed asked for, until it feeds or {@code seconds} have passed. */
    private Command holdUntilFeeding(double seconds) {
        Command command = step(true);
        for (double t = 0.0; t < seconds && shooter.state() != State.FEEDING; t += DT) {
            flywheelTicks = shooter.solution().feasible ? shooter.solution().flywheelTicksPerSecond : 0.0;
            command = step(true);
        }
        return command;
    }

    @Test
    void withoutTheButtonNothingRunsButTheTurretAndTheHoodAlreadyAreWhereTheyShouldBe() {
        Command command = step(false);
        for (int i = 0; i < 50; i++) {
            command = step(false);
        }
        assertEquals(State.IDLE, shooter.state());
        assertTrue(command.flywheelPower > 0.5, "the flywheels already run at the speed of the shot, with no button held");
        assertEquals(0.0, command.indexerPower, 0.0);
        assertFalse(command.stopperOpen);
        assertEquals(shooter.solution().hoodPosition, command.hoodPosition, 1e-9, "the hood is sent to the angle for the shot");
        assertTrue(command.hoodPosition > 0.2);
    }

    @Test
    void whileThePoseIsOnlyTheStartItSweepsLooksForTheHiveAndRefusesToShoot() {
        confirmed = false;
        double most = 0.0;
        double least = 0.0;
        for (double t = 0.0; t < 20.0; t += DT) {
            Command command = step(true);
            most = Math.max(most, command.turretPower);
            least = Math.min(least, command.turretPower);
            assertEquals(State.REFUSED, shooter.state());
            assertTrue(command.flywheelPower > 0.3, "the flywheels idle at about the speed of a shot from near here, not off");
            assertFalse(command.stopperOpen);
            assertEquals(0.0, command.indexerPower, 0.0);
            assertEquals(0.0, command.hoodPosition, 0.0, "the hood waits for a pose it can trust");
        }
        assertTrue(most > 0.1 && least < -0.1, "the turret is sent both ways: " + least + " to " + most);
        assertTrue(shooter.status().contains("camera has not seen"), shooter.status());
    }

    @Test
    void itSpinsUpAndOpensTheStopperAtOnceThenFeedsOnceEverythingHasBeenReadyForAWhileAndTheStopperHasHadTimeToOpen() {
        Command command = step(true);
        assertEquals(State.SPINNING_UP, shooter.state());
        assertTrue(command.flywheelPower > 0.5, "the flywheels are on while the button is held");
        assertTrue(command.stopperOpen, "the stopper starts opening with the flywheels, so it is open when they are ready");
        assertEquals(0.0, command.indexerPower, 0.0, "but nothing is pushed yet");

        // The turret is on the bearing and the flywheels at speed, but the hood's servo has only just been sent: not ready.
        flywheelTicks = shooter.solution().flywheelTicksPerSecond;
        for (int i = 0; i < 100; i++) {
            command = step(true);
        }
        assertEquals(State.SPINNING_UP, shooter.state(), "the hood is still moving: " + shooter.status());
        assertEquals(0.0, command.indexerPower, 0.0);

        double fed = -1.0;
        for (double t = 0.0; t < 15.0 && fed < 0.0; t += DT) {
            command = step(true);
            if (command.indexerPower > 0.0) {
                fed = now;
            }
        }
        assertTrue(fed > 0.0, shooter.status());
        assertTrue(command.stopperOpen);
        assertEquals(BiobuzzHoldShooter.FEED_POWER, command.indexerPower, 0.0);
        assertEquals(State.FEEDING, shooter.state());
    }

    @Test
    void theIndexerWaitsForTheStopperToOpenEvenWhenTheShotIsReadyAtOnce() {
        // A hood that is already where the shot wants it and flywheels already at speed: only the stopper's servo is left to wait for.
        step(false);
        for (int i = 0; i < 2000; i++) {
            step(false);
        }
        flywheelTicks = shooter.solution().flywheelTicksPerSecond;
        double pressed = now;
        double fed = -1.0;
        for (double t = 0.0; t < 15.0 && fed < 0.0; t += DT) {
            Command command = step(true);
            assertTrue(command.stopperOpen || state() == State.REFUSED);
            if (command.indexerPower > 0.0) {
                fed = now;
            }
        }
        assertTrue(fed - pressed >= BiobuzzHoldShooter.STOPPER_OPEN_SECONDS - DT, "fed " + (fed - pressed) + " s after the press");
        assertTrue(fed - pressed < BiobuzzHoldShooter.STOPPER_OPEN_SECONDS + 0.5, "and not much later: " + (fed - pressed));
    }

    private State state() {
        return shooter.state();
    }

    @Test
    void feedingPausesWhileTheFlywheelsSagOrTheRobotMovesAndResumesWhenAllIsWell() {
        Command command = holdUntilFeeding(30.0);
        for (int i = 0; i < 60; i++) {
            command = step(true);
        }
        assertEquals(BiobuzzHoldShooter.FEED_POWER, command.indexerPower, 0.0, shooter.status());
        double fed = shooter.fedSeconds();
        assertTrue(fed > 0.4);

        double target = shooter.solution().flywheelTicksPerSecond;
        flywheelTicks = 0.9 * target;
        command = step(true);
        assertEquals(0.0, command.indexerPower, 0.0, "a ball took the flywheels' speed: wait for it");
        assertTrue(command.stopperOpen, "the stopper stays open");
        assertEquals(fed, shooter.fedSeconds(), DT, "and the feed does not count");
        assertTrue(shooter.status().contains("paused"), shooter.status());
        flywheelTicks = target;
        speed = 0.5;
        assertEquals(0.0, step(true).indexerPower, 0.0, "nor does it feed while the robot is being driven");
        speed = 0.0;
        assertEquals(BiobuzzHoldShooter.FEED_POWER, step(true).indexerPower, 0.0);
    }

    @Test
    void lettingGoOfTheButtonStopsTheFeedAndShutsTheStopperButTheFlywheelsKeepTheirSpeed() {
        holdUntilFeeding(30.0);
        for (int i = 0; i < 60; i++) {
            step(true);
        }
        Command command = step(false);
        assertEquals(State.IDLE, shooter.state());
        assertFalse(command.stopperOpen);
        assertEquals(0.0, command.indexerPower, 0.0);
        assertTrue(command.flywheelPower > 0.5, "the flywheels stay at the speed of the shot");
        assertEquals(shooter.solution().hoodPosition, command.hoodPosition, 1e-9, "the hood and the turret stay on the target");

        // Pressing again starts over: spin up first, the stopper opening with it, nothing fed.
        command = step(true);
        assertEquals(State.SPINNING_UP, shooter.state());
        assertTrue(command.stopperOpen);
        assertEquals(0.0, command.indexerPower, 0.0);
        assertEquals(0.0, shooter.fedSeconds(), 0.0);
    }

    @Test
    void theTurretNeverLeavesItsWorkingRangeWhateverTheRobotDoesAndGoesTheLongWayRoundInsteadOfPastTheEnd() {
        // A turret that turns 184 degrees a second at full power (at most 74 with the limit on its power), under a robot that spins for
        // four turns and more, slowly enough for it to follow.
        double turret = 0.0;
        double least = 0.0;
        double most = 0.0;
        double heading = POSE.heading();
        for (double t = 0.0; t < 40.0; t += DT) {
            pose = new Pose(POSE.x(), POSE.y(), heading);
            Command command = shooter.update(now, false, pose, true, tilt, turret, 0.0, 0.0, 0.0);
            now += DT;
            turret += command.turretPower * 184.0 * DT;
            least = Math.min(least, turret);
            most = Math.max(most, turret);
            heading += Math.toRadians(40.0) * DT;
        }
        assertTrue(least > BiobuzzHoldShooter.TURRET_MIN_DEGREES - 3.0 && most < BiobuzzHoldShooter.TURRET_MAX_DEGREES + 3.0,
                "the turret went from " + least + " to " + most);
        assertTrue(most - least > 200.0, "and it did follow the HIVE round, the long way when it had to: " + least + " to " + most);

        // A bearing just past the end of the range, with the turret near it on the near side: it goes the long way, to the other end.
        BiobuzzHoldShooter fresh = new BiobuzzHoldShooter(Alliance.RED);
        double absolute = BiobuzzShotSolver.bearingDegrees(new Pose(POSE.x(), POSE.y(), 0.0), Alliance.RED, tilt);
        Pose behind = new Pose(POSE.x(), POSE.y(), Math.toRadians(absolute + 175.0));
        assertEquals(-175.0, BiobuzzShotSolver.bearingDegrees(behind, Alliance.RED, tilt), 3.0);
        assertTrue(fresh.update(0.0, false, behind, true, tilt, -160.0, 0.0, 0.0, 0.0).turretPower > 0.3,
                "-175 is 185 degrees on: the turret turns up through 0, not down past -170");
    }

    @Test
    void itRefusesAndSaysWhyWhereNoThrowGetsInAndWhenTheHiveHasTippedUnderIt() {
        holdUntilFeeding(30.0);
        assertEquals(State.FEEDING, shooter.state());
        // The HIVE tips while balls are going out: the CELL that faced up is down now and the other one opens to the far side.
        tilt = Tilt.SCORING_UP;
        Command command = step(true);
        assertEquals(State.REFUSED, shooter.state());
        assertFalse(command.stopperOpen);
        assertEquals(0.0, command.indexerPower, 0.0);
        assertTrue(command.flywheelPower > 0.5, "with no shot the flywheels stay about where they were, not off");
        assertTrue(shooter.status().contains("other side"), shooter.status());

        tilt = Tilt.AUDIENCE_UP;
        pose = new Pose(-1.4, -1.0, 0.0);
        command = step(true);
        assertEquals(State.REFUSED, shooter.state());
        assertTrue(shooter.status().contains("other side"), shooter.status());
        assertTrue(command.flywheelPower > 0.3, "nor on the wrong side of the HIVE");
    }
}
