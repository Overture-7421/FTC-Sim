package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.HardwareDevice;

/** Asks the hardware map for a device of a type nothing in the simulator makes. */
@TeleOp(name = "Unsupported Device", group = "Hardware")
public final class UnsupportedDeviceTeleOp extends OpMode {
    /** A device interface no hardware provider knows. */
    public interface MysteryDevice extends HardwareDevice {}

    @Override
    public void init() {
        hardwareMap.get(MysteryDevice.class, "mystery");
    }

    @Override
    public void loop() {}
}
