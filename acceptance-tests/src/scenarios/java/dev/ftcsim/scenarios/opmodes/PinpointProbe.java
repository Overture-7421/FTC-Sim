package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;

/**
 * Sets a Pinpoint up the way Pedro Pathing's PinpointLocalizer does with Hornet's constants (offsets as the forward
 * pod's Y and the strafe pod's X, the 4-bar pod's resolution, both encoders reversed, the pose and IMU reset, the
 * start pose set), waits until the chip is ready, and then reads it every loop as Pedro does: update, position, velocity.
 */
abstract class PinpointProbe extends OpMode {
    private final double forwardPodYInches;
    private final double strafePodXInches;
    private GoBildaPinpointDriver odo;

    PinpointProbe(double forwardPodYInches, double strafePodXInches) {
        this.forwardPodYInches = forwardPodYInches;
        this.strafePodXInches = strafePodXInches;
    }

    @Override
    public void init() {
        odo = hardwareMap.get(GoBildaPinpointDriver.class, "odopods");
        odo.setOffsets(forwardPodYInches, strafePodXInches, DistanceUnit.INCH);
        odo.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        odo.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.REVERSED, GoBildaPinpointDriver.EncoderDirection.REVERSED);
        odo.resetPosAndIMU();
        odo.setPosition(new Pose2D(DistanceUnit.MM, 0, 0, AngleUnit.RADIANS, 0));
        Probe.record("deviceId", odo.getDeviceID());
        Probe.record("deviceVersion", odo.getDeviceVersion());
    }

    @Override
    public void init_loop() {
        odo.update();
        switch (odo.getDeviceStatus()) {
            case CALIBRATING -> Probe.count("calibrating");
            case READY -> Probe.count("ready");
            default -> {
            }
        }
    }

    @Override
    public void loop() {
        odo.update();
        Pose2D pose = odo.getPosition();
        Probe.record("t", System.nanoTime());
        Probe.record("x", pose.getX(DistanceUnit.MM));
        Probe.record("y", pose.getY(DistanceUnit.MM));
        Probe.record("heading", pose.getHeading(AngleUnit.RADIANS));
        Probe.record("velX", odo.getVelX(DistanceUnit.MM));
        Probe.record("velY", odo.getVelY(DistanceUnit.MM));
        Probe.record("velH", odo.getHeadingVelocity(UnnormalizedAngleUnit.RADIANS));
        Probe.record("encoderX", odo.getEncoderX());
        Probe.record("encoderY", odo.getEncoderY());
        Probe.record("loopTime", odo.getLoopTime());
        Probe.record("status", odo.getDeviceStatus().ordinal());
    }
}
