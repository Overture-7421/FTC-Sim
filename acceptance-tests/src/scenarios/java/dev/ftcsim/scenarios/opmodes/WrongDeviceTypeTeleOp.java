package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.Servo;

/** Asks for one name as a motor and then as a servo, as two lines of a robot program that disagree about a device do. */
@TeleOp(name = "Wrong Device Type", group = "Hardware")
public final class WrongDeviceTypeTeleOp extends OpMode {
    @Override
    public void init() {
        hardwareMap.get(DcMotor.class, "arm");
        hardwareMap.get(Servo.class, "arm");
    }

    @Override
    public void loop() {}
}
