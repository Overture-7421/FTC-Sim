package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;

/**
 * What a robot that uses the camera for more than Hornet does sees: the tags the camera found, the pose from all of
 * them (MegaTag 1) and the pose from the robot's own heading (MegaTag 2). The heading it sends is the one the last
 * MegaTag 1 result gave, as a team would send its IMU's.
 */
@TeleOp(name = "Limelight Tags", group = "Devices")
public final class LimelightTagsTeleOp extends LinearOpMode {

    @Override
    public void runOpMode() {
        Limelight3A limelight = hardwareMap.get(Limelight3A.class, "limelight");
        limelight.setPollRateHz(100);
        limelight.pipelineSwitch(0);
        limelight.start();
        waitForStart();

        double headingDegrees = Double.NaN;
        while (opModeIsActive()) {
            LLResult result = limelight.getLatestResult();
            Probe.record("t", System.nanoTime());
            boolean valid = result != null && result.isValid();
            Probe.record("valid", valid ? 1 : 0);

            double fiducials = 0;
            double firstId = 0;
            double tagCount = 0;
            double x = 0;
            double y = 0;
            double mt2x = 0;
            double mt2y = 0;
            double mt2Yaw = 0;
            double avgDistance = 0;
            if (valid) {
                fiducials = result.getFiducialResults().size();
                for (FiducialResult fiducial : result.getFiducialResults()) {
                    firstId = fiducial.getFiducialId();
                    break;
                }
                tagCount = result.getBotposeTagCount();
                avgDistance = result.getBotposeAvgDist();
                Pose3D mt1 = result.getBotpose();
                x = mt1.getPosition().toUnit(DistanceUnit.INCH).x;
                y = mt1.getPosition().toUnit(DistanceUnit.INCH).y;
                Pose3D mt2 = result.getBotpose_MT2();
                mt2x = mt2.getPosition().toUnit(DistanceUnit.INCH).x;
                mt2y = mt2.getPosition().toUnit(DistanceUnit.INCH).y;
                mt2Yaw = mt2.getOrientation().getYaw(AngleUnit.DEGREES);
                if (tagCount > 0) {
                    headingDegrees = mt1.getOrientation().getYaw(AngleUnit.DEGREES);
                }
            }
            if (!Double.isNaN(headingDegrees)) {
                limelight.updateRobotOrientation(headingDegrees);
            }
            Probe.record("fiducials", fiducials);
            Probe.record("firstId", firstId);
            Probe.record("tagCount", tagCount);
            Probe.record("avgDistance", avgDistance);
            Probe.record("x", x);
            Probe.record("y", y);
            Probe.record("mt2x", mt2x);
            Probe.record("mt2y", mt2y);
            Probe.record("mt2Yaw", mt2Yaw);
            Probe.record("sentHeading", Double.isNaN(headingDegrees) ? 0 : headingDegrees);
            sleep(5);
        }
    }
}
