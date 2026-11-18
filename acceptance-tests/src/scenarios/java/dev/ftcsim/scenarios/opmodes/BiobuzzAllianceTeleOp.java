package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.firstinspires.ftc.robotcore.external.navigation.Position;

/**
 * The BIOBUZZ driver program, for one alliance: it scores by tipping its own alliance's HIVE. {@link BiobuzzRedTeleOp} aims at the
 * red HIVE and {@link BiobuzzBlueTeleOp} at the blue one; they differ in nothing else. It is written as a team writes it and knows
 * nothing about the simulator, so the same files run on the real robot once its names and offsets are checked: copy them into
 * {@code TeamCode} with {@link BiobuzzHardware} and the classes it uses, and change the package line.
 *
 * <p>The left stick drives the robot <b>as the driver sees the field from their wall</b>, whichever way the robot faces: up is away from
 * the driver (red looks towards +Y, blue towards -Y), and the heading it needs is the pose estimate's. The right stick turns the robot.
 * <b>Hold the left bumper to take balls in</b>: the intake runs, and the indexer slowly, so that the balls come up against the shut
 * stopper and wait there, preloaded. <b>Hold the right bumper to shoot</b>: the stopper opens and, when everything is ready, the
 * indexer feeds the balls; let go and the stopper shuts and the indexer stops. The flywheels are never off: they idle at the speed of
 * the shot (or, with no shot from where the robot stands, at about the speed of one from near it), so there is no spin-up when the
 * button goes down.
 *
 * <p>The turret always points at the CELL of the alliance's HIVE that faces up, the only one that can be loaded to tip the HIVE, and
 * the hood always sits at the angle for it ({@link BiobuzzShotSolver}); it is a steep lob that has to come down into a CELL that is
 * open only at the far end of its arm, so it works from the side of the HIVE the CELL opens towards, and the telemetry says when
 * it does not and where to go. The pose starts as an approximate start (the centre of the alliance's wall), until the Limelight
 * sees two tags of the HIVE together; the turret sweeps about where the HIVE should be until it does, and the shooter does not
 * feed until then. The tags' height says which way the HIVE is tipped ({@link BiobuzzHiveTracker}), so after a TIP the turret
 * turns to the other CELL.
 */
public abstract class BiobuzzAllianceTeleOp extends OpMode {
    private static final int TELEMETRY_INTERVAL_MS = 50;
    /** Camera frames are left out of the pose estimate while the turret or the robot turns faster than this, in degrees per second. */
    private static final double TURRET_RATE_LIMIT = 40.0;
    private static final double TURN_RATE_LIMIT = 60.0;
    /**
     * Nor are frames used while the robot drives faster than this, in metres a second: a frame is about a tenth of a second old by the
     * time the program has it (the camera's latency and the wait for the next frame), and in that time a robot at full speed has gone
     * well over ten centimetres, which the frame would put the estimate back by.
     */
    private static final double SPEED_LIMIT = 0.2;
    /** The centre start on each alliance's wall: 9.2 in from it, facing the field (the manual's G304); the robot may be anywhere along it. */
    private static final double WALL_START_Y = (70.52 - 9.2) * 0.0254;

    private final BiobuzzHive.Alliance alliance;
    private final BiobuzzPoseEstimator estimator;
    private final BiobuzzHiveTracker tracker;
    private final BiobuzzHoldShooter shooter;
    private BiobuzzHardware hw;
    private long lastLoopNanos;
    private double lastTurretDegrees;
    private double lastHeadingDegrees;

    protected BiobuzzAllianceTeleOp(BiobuzzHive.Alliance alliance) {
        this.alliance = alliance;
        double side = alliance.sign();
        // The red wall is at negative Y and its robot faces +Y; the blue one is the same turned half a turn.
        estimator = new BiobuzzPoseEstimator(new BiobuzzPoseEstimator.Pose(0.0, side * WALL_START_Y, -side * Math.PI / 2.0), true);
        tracker = new BiobuzzHiveTracker(alliance);
        shooter = new BiobuzzHoldShooter(alliance);
    }

    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(TELEMETRY_INTERVAL_MS);
        hw = new BiobuzzHardware(hardwareMap);
        estimator.setTags(BiobuzzHive.tags(alliance, tracker.tilt()));
    }

    @Override
    public void init_loop() {
        hw.odo.update();
        telemetry.addData("Pinpoint", hw.odo.getDeviceStatus());
        telemetry.addData("Alliance", alliance + ": aims at the " + alliance + " HIVE");
        telemetry.update();
    }

    @Override
    public void loop() {
        hw.odo.update();
        Pose2D pose = hw.odo.getPosition();
        int[] pulses = hw.readPulses();
        double turretDegrees = BiobuzzHardware.turretDegrees(pulses);
        long now = System.nanoTime();
        double seconds = lastLoopNanos == 0 ? 0.0 : (now - lastLoopNanos) / 1e9;
        lastLoopNanos = now;
        double headingDegrees = pose.getHeading(AngleUnit.DEGREES);
        double turretRate = seconds > 0 ? Math.abs(Math.IEEEremainder(turretDegrees - lastTurretDegrees, 360.0)) / seconds : 0.0;
        double turnRate = seconds > 0 ? Math.abs(Math.IEEEremainder(headingDegrees - lastHeadingDegrees, 360.0)) / seconds : 0.0;
        lastTurretDegrees = turretDegrees;
        lastHeadingDegrees = headingDegrees;
        double speed = Math.hypot(hw.odo.getVelX(DistanceUnit.METER), hw.odo.getVelY(DistanceUnit.METER));

        estimator.update(pose.getX(DistanceUnit.METER), pose.getY(DistanceUnit.METER), pose.getHeading(AngleUnit.RADIANS));
        LLResult result = hw.limelight.getLatestResult();
        boolean seen = result != null && result.isValid();
        // How the HIVE is tipped needs no pose and does not depend on how old the frame is or how the turret turns (the camera is fixed
        // to it), so every frame counts: the robot sees the tipped HIVE as it drives past, and may be stopped only on the other side.
        if (seen) {
            List<BiobuzzPoseEstimator.Sighting> sightings = sightings(result);
            if (tracker.update(sightings)) {
                estimator.setTags(BiobuzzHive.tags(alliance, tracker.tilt()));
            }
            // A frame is some tens of milliseconds old, and the camera turns with the turret: while either turns fast, or the robot
            // drives, the tags and the pose do not belong together, so those frames are left out of the pose.
            if (turretRate < TURRET_RATE_LIMIT && turnRate < TURN_RATE_LIMIT && speed < SPEED_LIMIT) {
                estimator.correct(sightings, Math.toRadians(turretDegrees));
            }
        }
        BiobuzzPoseEstimator.Pose estimate = estimator.pose();
        driveFieldOriented(estimate.heading());

        double flywheelTicks = hw.flywheelTicks();
        boolean intakeButton = gamepad1.left_bumper;
        BiobuzzHoldShooter.Command command = shooter.update(
                getRuntime(), gamepad1.right_bumper, estimate, estimator.confirmed(), tracker.tilt(), turretDegrees, flywheelTicks,
                speed, turnRate);
        hw.turretServo.setPower(command.turretPower);
        hw.hood.setPosition(command.hoodPosition);
        hw.stopper.setPosition(command.stopperOpen ? BiobuzzHardware.STOPPER_OPEN : BiobuzzHardware.STOPPER_SHUT);
        hw.setFlywheels(command.flywheelPower);
        // The intake roller also moves the balls along the lowest part of the path, where the indexer does not reach: the last of four
        // balls stays there unless the roller runs, so it runs whenever the shooter feeds.
        boolean intaking = intakeButton || command.indexerPower > 0.0;
        // Balls come in against the shut stopper: while the intake runs the indexer carries them slowly up to it, and they wait there.
        hw.intake.setPower(intaking ? BiobuzzHardware.INTAKE_POWER : 0.0);
        double indexerPower = command.indexerPower > 0.0 ? command.indexerPower
                : intakeButton && !command.stopperOpen ? BiobuzzHardware.STAGING_POWER : 0.0;
        hw.indexer.setPower(indexerPower);

        telemetry.addData("Alliance", String.format(Locale.ROOT, "%s: aims at the %s HIVE's %s CELL, the one facing up",
                alliance, alliance, tracker.tilt() == BiobuzzHive.Tilt.AUDIENCE_UP ? "audience" : "scoring"));
        telemetry.addData("Estimate", String.format(Locale.ROOT, "x=%.3f y=%.3f h=%.1f fixes=%d %s",
                estimate.x(), estimate.y(), Math.toDegrees(estimate.heading()), estimator.fixes(),
                estimator.confirmed() ? "confirmed" : "approximate: the start, until the camera sees the HIVE"));
        telemetry.addData("Aim", shooter.aim());
        telemetry.addData("Shooter", shooter.state() + ": " + shooter.status());
        telemetry.addData("Turret", String.format(Locale.ROOT, "deg=%.1f", turretDegrees));
        telemetry.addData("Mechanisms", String.format(Locale.ROOT, "hood=%.2f stopper=%s flywheels=%.0f intake=%s indexer=%s",
                command.hoodPosition, command.stopperOpen ? "open" : "shut", flywheelTicks, intaking ? "on" : "off",
                indexerPower > BiobuzzHardware.STAGING_POWER ? "feeding" : indexerPower > 0.0 ? "staging" : "off"));
        if (seen) {
            StringBuilder ids = new StringBuilder();
            for (FiducialResult tag : result.getFiducialResults()) {
                ids.append(ids.length() == 0 ? "" : ",").append(tag.getFiducialId());
            }
            telemetry.addData("Tags", ids.toString());
        } else {
            telemetry.addData("Tags", "none");
        }
        telemetry.update();
    }

    /**
     * Drives with the sticks as the driver sees the field from their wall: up on the left stick is away from the driver, towards the
     * far wall, whichever way the robot faces, and right is to the driver's right. The red driver stands at the red wall (negative Y)
     * and looks towards +Y, so their right is +X; the blue driver looks towards -Y, and their right is -X. The right stick turns the
     * robot, clockwise when pushed right.
     */
    private void driveFieldOriented(double headingRadians) {
        double forward = -gamepad1.left_stick_y;
        double right = gamepad1.left_stick_x;
        double side = -alliance.sign();
        // Where the driver wants to go on the field: +1 for the red driver, who looks towards +Y, -1 for the blue one.
        double fieldX = side * right;
        double fieldY = side * forward;
        // The same, on the robot's own axes.
        double ahead = fieldX * Math.cos(headingRadians) + fieldY * Math.sin(headingRadians);
        double toTheLeft = -fieldX * Math.sin(headingRadians) + fieldY * Math.cos(headingRadians);
        hw.drive(ahead, -toTheLeft, gamepad1.right_stick_x);
    }

    private static List<BiobuzzPoseEstimator.Sighting> sightings(LLResult result) {
        List<BiobuzzPoseEstimator.Sighting> sightings = new ArrayList<>();
        for (FiducialResult tag : result.getFiducialResults()) {
            Position at = tag.getTargetPoseCameraSpace().getPosition().toUnit(DistanceUnit.METER);
            sightings.add(new BiobuzzPoseEstimator.Sighting(tag.getFiducialId(), at.x, at.y, at.z));
        }
        return sightings;
    }

    @Override
    public void stop() {
        hw.stop();
    }
}
