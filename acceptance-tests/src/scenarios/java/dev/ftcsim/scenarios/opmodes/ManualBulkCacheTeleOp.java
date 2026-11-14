package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Bulk caching on manual: every read between two clears sees the data of the first. */
@TeleOp(name = "Bulk Cache Manual", group = "Hardware")
public final class ManualBulkCacheTeleOp extends BulkCacheProbe {
    public ManualBulkCacheTeleOp() {
        super(LynxModule.BulkCachingMode.MANUAL);
    }
}
