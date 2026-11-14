package dev.ftcsim.scenarios.hooks;

import android.content.Context;
import com.qualcomm.ftccommon.FtcEventLoop;
import java.io.IOException;
import java.net.ServerSocket;
import org.firstinspires.ftc.ftccommon.external.OnCreate;
import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop;

/** Hooks that go wrong in the ways libraries do; each one acts only when its flag is set. */
public final class FailingHooks {
    private FailingHooks() {}

    @OnCreate
    public static void throwsAnException(Context context) {
        if (Events.enabled("ftcsim.test.failingHook")) {
            throw new IllegalStateException("boom");
        }
    }

    /** The SDK catches exceptions from hooks but not errors such as a class the robot controller does not have. */
    @OnCreateEventLoop
    public static void throwsAnError(Context context, FtcEventLoop eventLoop) {
        if (Events.enabled("ftcsim.test.missingClassHook")) {
            throw new NoClassDefFoundError("android/missing/Thing");
        }
    }

    /** A library that starts a server, as many do, such as the one that serves a dashboard. */
    @OnCreate
    public static void listens(Context context) throws IOException {
        if (Events.enabled("ftcsim.test.listeningHook")) {
            new ServerSocket(18500).close();
        }
    }

    /** A library that starts a thread for its own work, as Panels does, and that then dies of what it could not do. */
    @OnCreateEventLoop
    public static void startsAThreadThatDies(Context context, FtcEventLoop eventLoop) {
        if (Events.enabled("ftcsim.test.dyingThreadHook")) {
            new Thread(() -> {
                throw new IllegalStateException("the library gave up");
            }, "library-start").start();
        }
    }
}
