package dev.ftcsim.scenarios.hooks;

import android.app.Activity;
import android.content.Context;
import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.util.WebHandlerManager;
import org.firstinspires.ftc.ftccommon.external.OnCreate;
import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop;
import org.firstinspires.ftc.ftccommon.external.OnDestroy;
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar;
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler;

/** What a library does with the robot controller's lifecycle hooks, noting what each hook was given. */
public final class LifecycleHooks {
    private LifecycleHooks() {}

    @OnCreate
    public static void created(Context context) {
        Events.record("OnCreate context=" + context.getClass().getSimpleName());
    }

    @OnCreateEventLoop
    public static void eventLoopCreated(Context context, FtcEventLoop eventLoop) {
        OpModeManagerImpl manager = eventLoop.getOpModeManager();
        boolean registered = manager == OpModeManagerImpl.getOpModeManagerOfActivity((Activity) context);
        Events.record("OnCreateEventLoop manager=" + manager.getClass().getSimpleName() + " registeredForActivity=" + registered);
    }

    @WebHandlerRegistrar
    public static void webHandlersRegistered(Context context, WebHandlerManager manager) {
        WebHandler handler = session -> null;
        manager.register("/hooks", handler);
        manager.getWebServer().getWebSocketManager().registerNamespaceAsBroadcastOnly("HOOKS");
        Events.record("WebHandlerRegistrar handler=" + (manager.getRegistered("/hooks") == handler)
                + " started=" + manager.getWebServer().wasStarted());
    }

    @OnDestroy
    public static void destroyed(Context context) {
        Events.record("OnDestroy");
    }
}
