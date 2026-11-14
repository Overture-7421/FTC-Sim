package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.TouchSensor;

/** Asks for one pin as a touch sensor and then as a digital channel, as two lines of a program that disagree about a device do. */
@TeleOp(name = "Wrong Pin Type", group = "Pins")
public final class WrongPinTypeTeleOp extends OpMode {
    @Override
    public void init() {
        hardwareMap.get(TouchSensor.class, "slideLimit");
        hardwareMap.get(DigitalChannel.class, "slideLimit");
    }

    @Override
    public void loop() {}
}
