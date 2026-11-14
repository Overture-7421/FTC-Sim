package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.bosch.BNO055IMU;
import com.qualcomm.hardware.lynx.LynxEmbeddedIMU;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.IMU;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/**
 * Asks for the Control Hub's IMU under one name, then for the same IMU under another name, which a robot configuration
 * would refuse, and then through the other API under the first name, which it allows. What it caught is in
 * {@link ImuSettings#messages}.
 */
@TeleOp(name = "IMU Names", group = "Devices")
public final class ImuNamesTeleOp extends OpMode {
    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(0);
        IMU first = hardwareMap.get(IMU.class, ImuSettings.name);
        Probe.record("first", 1);
        try {
            hardwareMap.get(IMU.class, ImuSettings.otherName);
            Probe.record("second", 1);
        } catch (RuntimeException refused) {
            ImuSettings.messages.add(refused.getClass().getSimpleName() + ": " + refused.getMessage());
            telemetry.addData("second name", refused.getMessage());
        }
        try {
            BNO055IMU legacy = hardwareMap.get(BNO055IMU.class, ImuSettings.name);
            Probe.record("legacyUnderTheSameName", 1);
            Probe.record("legacyIsTheEmbeddedDriver", legacy instanceof LynxEmbeddedIMU ? 1 : 0);
        } catch (RuntimeException refused) {
            ImuSettings.messages.add(refused.getClass().getSimpleName() + ": " + refused.getMessage());
        }
        Probe.record("yawOfTheFirst", first.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES));
        telemetry.update();
    }

    @Override
    public void loop() {}
}
