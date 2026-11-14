package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.digitalchickenlabs.OctoQuad;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/**
 * Uses an OctoQuad the way its SDK sample and a localizer integration do: the chip and firmware are checked, the
 * encoders are set up and bulk read, and the absolute localizer is configured for two tracking wheels on channels 0
 * and 1, reset, waited for, and bulk read together with the encoders.
 */
@TeleOp(name = "OctoQuad Localizer", group = "Devices")
public final class OctoQuadTeleOp extends OpMode {
    /** A goBILDA 4-bar pod: 2000 counts on a 32 mm wheel. */
    private static final float TICKS_PER_MM = 19.89436789f;
    /** The real tracking centre point is 4 mm behind and 113.5 mm right of the robot's centre; the offset undoes that. */
    private static final float TCP_OFFSET_X_MM = 4.0f;
    private static final float TCP_OFFSET_Y_MM = 113.5f;

    private OctoQuad octoquad;
    private DcMotorEx flywheel;
    private final OctoQuad.EncoderDataBlock encoders = new OctoQuad.EncoderDataBlock();
    private final OctoQuad.LocalizerDataBlock localizer = new OctoQuad.LocalizerDataBlock();

    @Override
    public void init() {
        octoquad = hardwareMap.get(OctoQuad.class, "octoquad");
        flywheel = hardwareMap.get(DcMotorEx.class, "flywheel");
        flywheel.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        Probe.record("chipId", octoquad.getChipId());
        OctoQuad.FirmwareVersion firmware = octoquad.getFirmwareVersion();
        Probe.record("firmwareMajor", firmware.maj);
        Probe.record("firmwareMinor", firmware.min);
        Probe.record("firmwareEngineering", firmware.eng);

        octoquad.resetAllPositions();
        octoquad.setSingleEncoderDirection(2, OctoQuad.EncoderDirection.REVERSE);
        octoquad.setSingleVelocitySampleInterval(2, 25);
        Probe.record("directionOfChannel2", octoquad.getSingleEncoderDirection(2).ordinal());
        Probe.record("velocityIntervalOfChannel2", octoquad.getSingleVelocitySampleInterval(2));

        octoquad.setAllLocalizerParameters(0, 1, TICKS_PER_MM, TICKS_PER_MM, TCP_OFFSET_X_MM, TCP_OFFSET_Y_MM, 1.0f, 25);
        octoquad.resetLocalizerAndCalibrateIMU();
    }

    @Override
    public void init_loop() {
        if (octoquad.getLocalizerStatus() == OctoQuad.LocalizerStatus.RUNNING) {
            Probe.count("running");
        }
    }

    @Override
    public void start() {
        flywheel.setPower(0.5);
    }

    @Override
    public void stop() {
        flywheel.setPower(0);
    }

    @Override
    public void loop() {
        octoquad.readLocalizerDataAndAllEncoderData(localizer, encoders);
        Probe.record("t", System.nanoTime());
        Probe.record("crcOk", localizer.crcOk && encoders.crcOk ? 1 : 0);
        Probe.record("status", localizer.localizerStatus.ordinal());
        Probe.record("x", localizer.posX_mm);
        Probe.record("y", localizer.posY_mm);
        Probe.record("heading", localizer.heading_rad);
        Probe.record("velX", localizer.velX_mmS);
        Probe.record("velY", localizer.velY_mmS);
        Probe.record("velHeading", localizer.velHeading_radS);
        Probe.record("encoder0", encoders.positions[0]);
        Probe.record("encoder1", encoders.positions[1]);
        Probe.record("encoder2", encoders.positions[2]);
        Probe.record("velocity2", encoders.velocities[2]);
    }
}
