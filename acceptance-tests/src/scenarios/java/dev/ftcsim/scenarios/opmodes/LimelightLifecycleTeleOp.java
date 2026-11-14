package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.limelightvision.LLFieldMap;
import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLStatus;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/**
 * Puts a Limelight through its states at known times and records what the driver says each time: running or not,
 * connected or not, how long since the last answer; and then makes every kind of request the camera can and cannot
 * answer, recording whether the driver reported success.
 */
@TeleOp(name = "Limelight Lifecycle", group = "Devices")
public final class LimelightLifecycleTeleOp extends LinearOpMode {

    @Override
    public void runOpMode() {
        Limelight3A limelight = hardwareMap.get(Limelight3A.class, "limelight");
        snapshot(limelight, "fresh");
        waitForStart();

        limelight.setPollRateHz(100);
        limelight.start();
        sleep(200);
        snapshot(limelight, "started");

        limelight.pause();
        sleep(600);
        snapshot(limelight, "paused");

        // The schedule of the polling thread outlives a pause, so a new rate changes nothing until the camera is stopped.
        limelight.setPollRateHz(1);
        limelight.start();
        sleep(200);
        snapshot(limelight, "resumed");

        limelight.setPollRateHz(250);
        sleep(300);
        snapshot(limelight, "rateIgnoredWhileRunning");

        limelight.stop();
        sleep(600);
        snapshot(limelight, "stopped");

        // At 1 Hz the camera is polled once a second: connected for a quarter of a second after each answer.
        limelight.setPollRateHz(0);
        limelight.start();
        sleep(300);
        snapshot(limelight, "slowAfter300");
        sleep(800);
        snapshot(limelight, "slowAfter1100");

        Probe.record("switch", limelight.pipelineSwitch(0) ? 1 : 0);
        Probe.record("reload", limelight.reloadPipeline() ? 1 : 0);
        Probe.record("orientation", limelight.updateRobotOrientation(90) ? 1 : 0);
        LLStatus status = limelight.getStatus();
        Probe.record("statusFps", status.getFps());
        Probe.record("statusPipeline", status.getPipelineIndex());
        Probe.record("statusType", status.getPipelineType().equals("pipe_fiducial") ? 1 : 0);

        Probe.record("snapshot", limelight.captureSnapshot("test") ? 1 : 0);
        Probe.record("deleteSnapshots", limelight.deleteSnapshots() ? 1 : 0);
        Probe.record("pythonEmpty", limelight.updatePythonInputs(new double[0]) ? 1 : 0);
        Probe.record("pythonTooMany", limelight.updatePythonInputs(new double[33]) ? 1 : 0);
        Probe.record("python", limelight.updatePythonInputs(new double[] {1, 2, 3}) ? 1 : 0);
        Probe.record("uploadPipeline", limelight.uploadPipeline("{}", 1) ? 1 : 0);
        Probe.record("uploadPython", limelight.uploadPython("print(1)", null) ? 1 : 0);
        Probe.record("uploadEmptyMap", limelight.uploadFieldmap(new LLFieldMap(), null) ? 1 : 0);
        Probe.record("calibration", limelight.getCalDefault().isValid() ? 1 : 0);

        LLResult result = limelight.getLatestResult();
        Probe.record("finalResultNotNull", result != null ? 1 : 0);
        limelight.close();
        Probe.record("closed", limelight.isRunning() ? 0 : 1);
    }

    private void snapshot(Limelight3A limelight, String phase) {
        LLResult result = limelight.getLatestResult();
        Probe.record(phase + ".running", limelight.isRunning() ? 1 : 0);
        Probe.record(phase + ".connected", limelight.isConnected() ? 1 : 0);
        Probe.record(phase + ".since", limelight.getTimeSinceLastUpdate());
        Probe.record(phase + ".valid", result != null && result.isValid() ? 1 : 0);
        Probe.record(phase + ".staleness", result == null ? -1 : result.getStaleness());
    }
}
