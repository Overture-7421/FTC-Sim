package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.util.ElapsedTime;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;

/** Runs from RED wall, centre with four POLLEN. Every stage has a deadline and reports its outcome. */
@Autonomous(name = "BIOBUZZ Flower Demo", group = "BIOBUZZ")
public final class BiobuzzFlowerAuto extends OpMode {
    private enum Stage { SPIN_UP, SHOOT, CLEAR_WALL, ALIGN, APPROACH, NUDGE, COLLECT, COMPLETE, FAILED }

    private static final double INCH = 0.0254;
    private static final double FLOWER_X = 23.39 * INCH;
    private static final double FLOWER_Y = -69.52 * INCH;
    // Four degrees of linkage travel lifts the plate far enough to clear the FLOWER lip.
    private static final double COLIBRI_NUDGE = 4.0 * (40.0 / 30.0) / 1800.0;
    private final ElapsedTime timer = new ElapsedTime();
    private BiobuzzHardware hw;
    private Stage stage;
    private String result = "Start at RED wall, centre with four POLLEN";
    private Pose2D pose;

    @Override
    public void init() {
        hw = new BiobuzzHardware(hardwareMap);
        telemetry.addData("Setup", result);
    }

    @Override
    public void start() {
        hw.odo.setPosition(new Pose2D(DistanceUnit.METER, 0, -62.8 * INCH, AngleUnit.RADIANS, Math.PI / 2));
        hw.hood.setPosition(0.22);
        enter(Stage.SPIN_UP);
    }

    @Override
    public void loop() {
        hw.odo.update();
        pose = hw.odo.getPosition();
        switch (stage) {
            case SPIN_UP:
                hw.setFlywheels(0.8);
                double turret = BiobuzzHardware.turretDegrees(hw.readPulses());
                hw.turretServo.setPower(turret < 12 ? 0.2 : 0);
                if (turret >= 12 && hw.flywheelTicks() > 900 && nearStopper()) {
                    hw.stopper.setPosition(BiobuzzHardware.STOPPER_OPEN);
                    enter(Stage.SHOOT);
                }
                break;
            case SHOOT:
                hw.intake.setPower(1);
                hw.indexer.setPower(0.6);
                if (timer.seconds() > 1.5 && !nearStopper() && hw.intakeSensor.getDistance(DistanceUnit.CM) > 10) {
                    hw.setFlywheels(0);
                    hw.intake.setPower(0);
                    hw.indexer.setPower(0);
                    hw.stopper.setPosition(BiobuzzHardware.STOPPER_SHUT);
                    enter(Stage.CLEAR_WALL);
                }
                break;
            case CLEAR_WALL:
                if (driveTo(0, -1.16, Math.PI / 2)) {
                    enter(Stage.ALIGN);
                }
                break;
            case ALIGN:
                if (driveTo(FLOWER_X, -1.16, -Math.PI / 2)) {
                    enter(Stage.APPROACH);
                }
                break;
            case APPROACH:
                if (driveTo(FLOWER_X, FLOWER_Y + 0.325, -Math.PI / 2)) {
                    enter(Stage.NUDGE);
                }
                break;
            case NUDGE:
                hw.intake.setPower(1);
                hw.colibriLeft.setPosition(COLIBRI_NUDGE);
                hw.colibriRight.setPosition(COLIBRI_NUDGE);
                if (timer.seconds() > 0.25) {
                    enter(Stage.COLLECT);
                }
                break;
            case COLLECT:
                hw.intake.setPower(1);
                hw.indexer.setPower(BiobuzzHardware.STAGING_POWER);
                driveTo(FLOWER_X, FLOWER_Y + 0.30, -Math.PI / 2);
                if (timer.seconds() > 2.0 && nearStopper()) {
                    result = "Preloads fired. FLOWER pickup at upper sensor.";
                    hw.stop();
                    enter(Stage.COMPLETE);
                }
                break;
            default:
                break;
        }
        if (stage != Stage.COMPLETE && stage != Stage.FAILED && timer.seconds() > 5) {
            result = "Timed out during " + stage;
            hw.stop();
            enter(Stage.FAILED);
        }
        telemetry.addData("Pose", pose);
        LLResult camera = hw.limelight.getLatestResult();
        telemetry.addData("Camera", camera == null ? "Waiting for frame" : camera.getFiducialResults().size() + " tags");
        telemetry.addData("Stage", stage);
        telemetry.addData("Result", result);
        telemetry.update();
    }

    private boolean nearStopper() {
        return hw.upSensor.getDistance(DistanceUnit.CM) < 5;
    }

    private void enter(Stage next) {
        stage = next;
        timer.reset();
    }

    private boolean driveTo(double x, double y, double yaw) {
        double dx = x - pose.getX(DistanceUnit.METER);
        double dy = y - pose.getY(DistanceUnit.METER);
        double heading = pose.getHeading(AngleUnit.RADIANS);
        double error = Math.IEEEremainder(yaw - heading, 2 * Math.PI);
        if (Math.hypot(dx, dy) < 0.016 && Math.abs(error) < 0.024) {
            hw.drive(0, 0, 0);
            return true;
        }
        double forward = dx * Math.cos(heading) + dy * Math.sin(heading);
        double right = dx * Math.sin(heading) - dy * Math.cos(heading);
        hw.drive(clip(4.0 * forward), clip(4.0 * right), clip(-2.5 * error));
        return false;
    }

    private static double clip(double power) {
        return Math.max(-0.48, Math.min(0.48, power));
    }

    @Override
    public void stop() {
        hw.stop();
    }
}
