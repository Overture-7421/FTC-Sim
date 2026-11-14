package dev.ftcsim.sdk;

import android.app.Activity;
import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.hardware.HardwareFactory;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;

/**
 * The SDK's event loop, which libraries receive through their {@code @OnCreateEventLoop} hooks, built by its own
 * constructor. Its OpMode manager is the exception: the constructor creates one it cannot be told to replace, so
 * the event loop hands out the manager that runs the robot program's OpModes instead. The loop itself is never
 * run; {@link SdkRobotHost} plays its part.
 *
 * <p>The constructor registers the manager it creates for the activity it is given. A separate activity keeps that
 * unused manager from taking the place of the real one in the SDK's activity-to-manager table.
 */
final class SimEventLoop extends FtcEventLoop {
    private final OpModeManagerImpl hostManager;

    SimEventLoop(Activity ownActivity, OpModeManagerImpl hostManager) {
        // No driver station UI to update and no OpModes beyond the annotated ones, which the catalog registers.
        super(new HardwareFactory(ownActivity), manager -> {}, null, ownActivity);
        this.hostManager = hostManager;
    }

    @Override
    public OpModeManagerImpl getOpModeManager() {
        return hostManager;
    }
}
