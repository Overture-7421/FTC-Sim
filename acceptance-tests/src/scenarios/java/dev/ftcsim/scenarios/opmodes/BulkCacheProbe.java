package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import java.util.List;

/**
 * Reads the same encoder three times: right after the bulk cache was cleared, again 20 ms later without clearing
 * it, and once more after clearing it. What each read returns depends on the caching mode of the hub.
 */
abstract class BulkCacheProbe extends OpMode {
    private final LynxModule.BulkCachingMode mode;
    private List<LynxModule> hubs;
    private DcMotorEx flywheel;

    BulkCacheProbe(LynxModule.BulkCachingMode mode) {
        this.mode = mode;
    }

    @Override
    public void init() {
        hubs = hardwareMap.getAll(LynxModule.class);
        for (LynxModule hub : hubs) {
            hub.setBulkCachingMode(mode);
        }
        flywheel = hardwareMap.get(DcMotorEx.class, "flywheel");
        flywheel.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
    }

    @Override
    public void start() {
        flywheel.setPower(1.0);
    }

    @Override
    public void loop() {
        for (LynxModule hub : hubs) {
            hub.clearBulkCache();
        }
        Probe.record("fresh", flywheel.getCurrentPosition());
        try {
            Thread.sleep(20);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        Probe.record("later", flywheel.getCurrentPosition());
    }
}
