package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/**
 * A lift that the left stick drives, with the pins around it that a team puts on one. Every pass of the loop shows what
 * those pins read through telemetry, together with the lift motor's own encoder, so that a test can compare the
 * pins with the shaft they belong to.
 */
abstract class SlideProbe extends OpMode {
    private DcMotorEx slide;

    /** Looks up the pins the scenario reads. */
    abstract void find();

    /** Adds what the pins read to the telemetry. */
    abstract void show();

    @Override
    public void init() {
        telemetry.setMsTransmissionInterval(0);
        slide = hardwareMap.get(DcMotorEx.class, "slide");
        slide.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        slide.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        find();
    }

    @Override
    public void loop() {
        slide.setPower(-gamepad1.left_stick_y);
        show();
        telemetry.addData("slide counts", slide.getCurrentPosition());
    }

    @Override
    public void stop() {
        slide.setPower(0);
    }
}
