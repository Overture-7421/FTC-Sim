package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.firstinspires.ftc.robotcore.external.navigation.Position;

/**
 * A diagnostic program for the BIOBUZZ robot, everything by hand: it checks that the robot drives, that the Pinpoint ("odopods")
 * reports where it went, that the Limelight (which rides on the turret) sees the HIVE tags and that the turret turns and is read
 * back. Everything it learns is on telemetry. It is written as a team writes it and knows nothing about the simulator, so the same
 * file runs on the real robot once its names and offsets are checked: copy it into {@code TeamCode} and change the package line.
 * The programs that score are {@link BiobuzzRedTeleOp} and {@link BiobuzzBlueTeleOp}.
 *
 * <p>Left stick drives and strafes, right stick turns the robot. The bumpers turn the turret (left bumper to its left). The left
 * trigger runs the intake by hand, and the indexer slowly with it, so that the balls come up against the shut stopper and wait
 * there, preloaded. The right trigger runs the flywheels, B feeds the indexer, X opens the stopper, Y raises the colibri arms and
 * the D-pad's up and down raise and lower the hood. The pose is {@link BiobuzzPoseEstimator}'s: the Pinpoint's odometry corrected
 * by the Limelight's 3D targets ({@link BiobuzzHive} has the tags); the estimate starts when the camera sees two tags together,
 * so drive up to a HIVE first. The turret's angle comes from its two REV Through Bore Encoders on the OctoQuad, in absolute mode.
 */
@TeleOp(name = "BIOBUZZ Stub", group = "Stub")
public final class BiobuzzStubTeleOp extends OpMode {
    private static final int TELEMETRY_INTERVAL_MS = 50;
    private static final double TURRET_POWER = 0.3;
    /** Camera frames are left out of the pose estimate while the turret or the robot turns faster than this, in degrees per second. */
    private static final double TURRET_RATE_LIMIT = 40.0;
    private static final double TURN_RATE_LIMIT = 60.0;
    /** And while the robot drives faster than this, in metres a second: a frame is about a tenth of a second old when the program has it. */
    private static final double SPEED_LIMIT = 0.2;
    /** How fast the hood moves per second of the D-pad: its servo has five turns, up to position 0.12 for the hood's 44 degrees. */
    private static final double HOOD_PER_SECOND = 0.05;

    private BiobuzzHardware hw;
    private double hoodPosition;
    private long lastLoopNanos;
    private double lastTurretDegrees;
    private double lastHeadingDegrees;
    private final BiobuzzPoseEstimator estimator = new BiobuzzPoseEstimator(null);

    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(TELEMETRY_INTERVAL_MS);
        hw = new BiobuzzHardware(hardwareMap);
    }

    @Override
    public void init_loop() {
        hw.odo.update();
        telemetry.addData("Pinpoint", hw.odo.getDeviceStatus());
        telemetry.update();
    }

    @Override
    public void loop() {
        hw.drive(-gamepad1.left_stick_y, gamepad1.left_stick_x, gamepad1.right_stick_x);

        hw.odo.update();
        Pose2D pose = hw.odo.getPosition();
        telemetry.addData("Pinpoint", hw.odo.getDeviceStatus());
        telemetry.addData("Pose", String.format(Locale.ROOT, "x=%.1f y=%.1f h=%.1f",
                pose.getX(DistanceUnit.MM), pose.getY(DistanceUnit.MM), pose.getHeading(AngleUnit.DEGREES)));
        telemetry.addData("Velocity", String.format(Locale.ROOT, "x=%.1f y=%.1f",
                hw.odo.getVelX(DistanceUnit.MM), hw.odo.getVelY(DistanceUnit.MM)));
        telemetry.addData("Encoders", String.format(Locale.ROOT, "x=%d y=%d", hw.odo.getEncoderX(), hw.odo.getEncoderY()));

        LLResult result = hw.limelight.getLatestResult();
        boolean seen = result != null && result.isValid();

        int[] pulses = hw.readPulses();
        double turretDegrees = BiobuzzHardware.turretDegrees(pulses);
        long now = System.nanoTime();
        double seconds = lastLoopNanos == 0 ? 0.0 : (now - lastLoopNanos) / 1e9;
        lastLoopNanos = now;
        double turretRate = seconds > 0 ? Math.abs(Math.IEEEremainder(turretDegrees - lastTurretDegrees, 360.0)) / seconds : 0.0;
        double turnRate = seconds > 0
                ? Math.abs(Math.IEEEremainder(pose.getHeading(AngleUnit.DEGREES) - lastHeadingDegrees, 360.0)) / seconds : 0.0;
        lastTurretDegrees = turretDegrees;
        lastHeadingDegrees = pose.getHeading(AngleUnit.DEGREES);

        estimator.update(pose.getX(DistanceUnit.METER), pose.getY(DistanceUnit.METER), pose.getHeading(AngleUnit.RADIANS));
        // A frame is some tens of milliseconds old, and the camera turns with the turret: while either turns fast the tags and the
        // turret angle do not belong together, so those frames are left out.
        double speed = Math.hypot(hw.odo.getVelX(DistanceUnit.METER), hw.odo.getVelY(DistanceUnit.METER));
        if (seen && turretRate < TURRET_RATE_LIMIT && turnRate < TURN_RATE_LIMIT && speed < SPEED_LIMIT) {
            List<BiobuzzPoseEstimator.Sighting> sightings = new ArrayList<>();
            for (FiducialResult tag : result.getFiducialResults()) {
                Position at = tag.getTargetPoseCameraSpace().getPosition().toUnit(DistanceUnit.METER);
                sightings.add(new BiobuzzPoseEstimator.Sighting(tag.getFiducialId(), at.x, at.y, at.z));
            }
            estimator.correct(sightings, Math.toRadians(turretDegrees));
        }
        BiobuzzPoseEstimator.Pose estimate = estimator.pose();
        telemetry.addData("Estimate", estimate == null
                ? "none yet: the camera has not seen two tags together"
                : String.format(Locale.ROOT, "x=%.3f y=%.3f h=%.1f fixes=%d",
                        estimate.x(), estimate.y(), Math.toDegrees(estimate.heading()), estimator.fixes()));

        double flywheelTicks = hw.flywheelTicks();
        hw.turretServo.setPower((gamepad1.left_bumper ? TURRET_POWER : 0.0) - (gamepad1.right_bumper ? TURRET_POWER : 0.0));
        telemetry.addData("Turret", String.format(Locale.ROOT, "deg=%.1f p0=%d p1=%d", turretDegrees, pulses[0], pulses[1]));

        hoodPosition = Math.max(0.0, Math.min(BiobuzzShotSolver.HOOD_MAX_POSITION,
                hoodPosition + HOOD_PER_SECOND * seconds * ((gamepad1.dpad_up ? 1 : 0) - (gamepad1.dpad_down ? 1 : 0))));
        hw.hood.setPosition(hoodPosition);
        hw.stopper.setPosition(gamepad1.x ? BiobuzzHardware.STOPPER_OPEN : BiobuzzHardware.STOPPER_SHUT);
        hw.colibriLeft.setPosition(gamepad1.y ? BiobuzzHardware.COLIBRI_UP : BiobuzzHardware.COLIBRI_DOWN);
        hw.colibriRight.setPosition(gamepad1.y ? BiobuzzHardware.COLIBRI_UP : BiobuzzHardware.COLIBRI_DOWN);

        // The intake is by hand. Balls come in against the shut stopper: while the left trigger runs the intake the indexer
        // carries them slowly up to it (too slowly to feed the flywheels), and they wait there, preloaded, until a shot.
        boolean intaking = gamepad1.left_trigger > 0.3;
        hw.intake.setPower(intaking ? BiobuzzHardware.INTAKE_POWER : 0.0);
        hw.setFlywheels(gamepad1.right_trigger > 0.3 ? 0.6 : 0.0);
        double indexerPower = gamepad1.b ? BiobuzzHardware.FEED_POWER : intaking && !gamepad1.x ? BiobuzzHardware.STAGING_POWER : 0.0;
        hw.indexer.setPower(indexerPower);
        telemetry.addData("Mechanisms", String.format(Locale.ROOT, "hood=%.3f stopper=%s colibri=%s intake=%s flywheels=%.0f indexer=%s",
                hoodPosition, gamepad1.x ? "open" : "shut", gamepad1.y ? "up" : "down",
                intaking ? "on" : "off", flywheelTicks, indexerPower > 0 ? "on" : "off"));

        if (seen) {
            StringBuilder ids = new StringBuilder();
            for (FiducialResult tag : result.getFiducialResults()) {
                ids.append(ids.length() == 0 ? "" : ",").append(tag.getFiducialId());
            }
            telemetry.addData("Tags", ids.toString());
            telemetry.addData("Target", String.format(Locale.ROOT, "tx=%.2f ty=%.2f ta=%.4f",
                    result.getTx(), result.getTy(), result.getTa()));
            telemetry.addData("Botpose tags", result.getBotposeTagCount());
        } else {
            telemetry.addData("Tags", "none");
        }
        telemetry.update();
    }

    @Override
    public void stop() {
        hw.stop();
    }
}
