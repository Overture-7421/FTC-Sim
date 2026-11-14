package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.bosch.BHI260IMU;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Asks for the IMU of the newer Control Hub by its driver class. What it caught is in {@link ImuSettings#messages}. */
@TeleOp(name = "BHI260 IMU", group = "Devices")
public final class Bhi260TeleOp extends OpMode {
    @Override
    public void init() {
        try {
            hardwareMap.get(BHI260IMU.class, ImuSettings.name);
            Probe.record("found", 1);
        } catch (Throwable failure) {
            ImuSettings.messages.add(failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    @Override
    public void loop() {}
}
