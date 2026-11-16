package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.lynx.LynxEmbeddedBNO055IMUNew;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

/**
 * Uses an IMU through the new interface as a team's program does: looks it up by the name {@link ImuSettings} gives,
 * tells the SDK how the hub is mounted, and then reads yaw, pitch and roll, the orientation in the other forms the
 * interface has, and the angular velocity in every loop.
 */
abstract class ImuProbe extends OpMode {
    private IMU imu;
    private long startedAt;
    private boolean yawWasReset;

    /** The IMU, looked up the way the scenario wants to look it up. */
    abstract IMU find();

    /** What the code tells the SDK about how the IMU is mounted. */
    abstract ImuOrientationOnRobot mounting();

    @Override
    public void init() {
        imu = find();
        Probe.record("embeddedDriver", imu instanceof LynxEmbeddedBNO055IMUNew ? 1 : 0);
        if (ImuSettings.initialize) {
            Probe.record("initialized", imu.initialize(new IMU.Parameters(mounting())) ? 1 : 0);
        }
        Probe.record("initDone", System.nanoTime());
    }

    @Override
    public void start() {
        startedAt = System.nanoTime();
    }

    @Override
    public void loop() {
        if (!yawWasReset && ImuSettings.resetYawAtSeconds >= 0 && System.nanoTime() - startedAt >= ImuSettings.resetYawAtSeconds * 1e9) {
            imu.resetYaw();
            yawWasReset = true;
            Probe.record("resetAt", System.nanoTime());
        }
        YawPitchRollAngles angles = imu.getRobotYawPitchRollAngles();
        Orientation orientation = imu.getRobotOrientation(AxesReference.INTRINSIC, AxesOrder.ZXY, AngleUnit.DEGREES);
        Quaternion quaternion = imu.getRobotOrientationAsQuaternion();
        AngularVelocity rate = imu.getRobotAngularVelocity(AngleUnit.RADIANS);
        Probe.record("t", System.nanoTime());
        Probe.record("yaw", angles.getYaw(AngleUnit.DEGREES));
        Probe.record("pitch", angles.getPitch(AngleUnit.DEGREES));
        Probe.record("roll", angles.getRoll(AngleUnit.DEGREES));
        Probe.record("firstAngle", orientation.firstAngle);
        Probe.record("secondAngle", orientation.secondAngle);
        Probe.record("thirdAngle", orientation.thirdAngle);
        Probe.record("qw", quaternion.w);
        Probe.record("qx", quaternion.x);
        Probe.record("qy", quaternion.y);
        Probe.record("qz", quaternion.z);
        Probe.record("rateX", rate.xRotationRate);
        Probe.record("rateY", rate.yRotationRate);
        Probe.record("rateZ", rate.zRotationRate);
    }
}
