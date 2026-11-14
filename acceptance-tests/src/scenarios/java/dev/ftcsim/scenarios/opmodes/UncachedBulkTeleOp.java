package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** Bulk caching off: every read asks the hub, so reads at different times differ. */
@TeleOp(name = "Bulk Cache Off", group = "Hardware")
public final class UncachedBulkTeleOp extends BulkCacheProbe {
    public UncachedBulkTeleOp() {
        super(LynxModule.BulkCachingMode.OFF);
    }
}
