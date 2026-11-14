package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;
import org.firstinspires.ftc.robotcore.external.navigation.Position;

/**
 * Makes the calls that Hornet's {@code Camera} subsystem makes, in the order it makes them: the camera is set up once
 * (poll rate, pipeline 0, start), and every loop it reads the latest result, drops it unless it is valid, and turns the
 * robot pose it carries into inches and radians in Pedro Pathing's frame. Everything it sees is recorded for the test,
 * and shown on telemetry as a robot program would.
 */
@TeleOp(name = "Limelight Pose", group = "Devices")
public final class LimelightPoseTeleOp extends LinearOpMode {
    private static final double LOOP_MILLIS = 5;

    @Override
    public void runOpMode() {
        Probe.transmitTelemetryImmediately(telemetry);
        Limelight3A limelight = hardwareMap.get(Limelight3A.class, "limelight");
        limelight.setPollRateHz(100);
        limelight.pipelineSwitch(0);
        limelight.start();
        telemetry.addData("Limelight", "started");
        telemetry.update();
        waitForStart();

        while (opModeIsActive()) {
            LLResult result = limelight.getLatestResult();
            Probe.record("t", System.nanoTime());
            Probe.record("connected", limelight.isConnected() ? 1 : 0);
            boolean valid = result != null && result.isValid();
            Probe.record("valid", valid ? 1 : 0);

            double x = 0;
            double y = 0;
            double yaw = 0;
            double pedroX = 0;
            double pedroY = 0;
            double pedroHeading = 0;
            double tags = 0;
            double firstId = 0;
            double staleness = 0;
            double tx = 0;
            double ty = 0;
            double ta = 0;
            double latency = 0;
            double tagCount = 0;
            if (valid) {
                Pose3D robotPose = result.getBotpose();
                Position position = robotPose.getPosition().toUnit(DistanceUnit.INCH);
                x = position.x;
                y = position.y;
                yaw = robotPose.getOrientation().getYaw(AngleUnit.RADIANS);
                // Hornet's convertToPedro: rotate by -90 degrees, heading included, then move the origin to (72, 72).
                pedroX = y + 72;
                pedroY = 72 - x;
                pedroHeading = Math.atan2(Math.sin(yaw - Math.PI / 2), Math.cos(yaw - Math.PI / 2));
                tags = result.getFiducialResults().size();
                for (FiducialResult fiducial : result.getFiducialResults()) {
                    firstId = fiducial.getFiducialId();
                    break;
                }
                staleness = result.getStaleness();
                tx = result.getTx();
                ty = result.getTy();
                ta = result.getTa();
                latency = result.getCaptureLatency() + result.getTargetingLatency();
                tagCount = result.getBotposeTagCount();
                telemetry.addData("Pose", "%.1f in, %.1f in, %.3f rad", pedroX, pedroY, pedroHeading);
            } else {
                telemetry.addData("Pose", "no target");
            }
            telemetry.update();
            Probe.record("x", x);
            Probe.record("y", y);
            Probe.record("yaw", yaw);
            Probe.record("pedroX", pedroX);
            Probe.record("pedroY", pedroY);
            Probe.record("pedroHeading", pedroHeading);
            Probe.record("tags", tags);
            Probe.record("firstId", firstId);
            Probe.record("staleness", staleness);
            Probe.record("tx", tx);
            Probe.record("ty", ty);
            Probe.record("ta", ta);
            Probe.record("latency", latency);
            Probe.record("tagCount", tagCount);
            sleep((long) LOOP_MILLIS);
        }
    }
}
