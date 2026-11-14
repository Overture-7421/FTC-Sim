package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.TouchSensor;

/**
 * Reads all three kinds of pin with bulk caching on automatic, as teams who want fast loops do: the SDK then asks the hub
 * for all inputs at once and answers each pin from that one bulk read.
 */
@TeleOp(name = "Bulk Pins", group = "Pins")
public final class BulkPinsTeleOp extends SlideProbe {
    private TouchSensor limit;
    private DigitalChannel beam;
    private AnalogInput pot;

    @Override
    void find() {
        for (LynxModule module : hardwareMap.getAll(LynxModule.class)) {
            module.setBulkCachingMode(LynxModule.BulkCachingMode.AUTO);
        }
        limit = hardwareMap.get(TouchSensor.class, "slideLimit");
        beam = hardwareMap.get(DigitalChannel.class, "beam");
        pot = hardwareMap.get(AnalogInput.class, "slidePot");
    }

    @Override
    void show() {
        telemetry.addData("touch pressed", limit.isPressed());
        telemetry.addData("beam state", beam.getState());
        telemetry.addData("pot volts", pot.getVoltage());
    }
}
