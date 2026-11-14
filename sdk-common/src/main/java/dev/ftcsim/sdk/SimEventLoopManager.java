package dev.ftcsim.sdk;

import android.content.Context;
import com.qualcomm.robotcore.eventloop.EventLoopManager;
import com.qualcomm.robotcore.eventloop.opmode.EventLoopManagerClient;
import com.qualcomm.robotcore.robocol.TelemetryMessage;
import com.qualcomm.robotcore.util.WebServer;
import org.firstinspires.ftc.robotcore.internal.opmode.OnBotJavaHelper;

/**
 * The SDK's event loop manager in the role the OpMode manager needs of it: its presence switches on the SDK's
 * clearing of telemetry and of the global error and warning at every Init, and it is the funnel the SDK sends
 * telemetry through. It is never started, so there is no network, no heartbeat and no event loop thread; the
 * simulator runs the loop itself and the telemetry goes to the display instead of to a Driver Station.
 *
 * <p>Everything else the SDK would send to a Driver Station (toasts, dialogs, text to speech) is queued by the
 * SDK's network handler, which has no peer to send it to, so it stays unsent. Gamepad rumble and LED effects stay
 * in the gamepads' queues, because the SDK's FtcEventLoopHandler, which would forward them, is not part of the
 * simulated controller.
 */
final class SimEventLoopManager extends EventLoopManager {
    private final DriverStationDisplay display;

    SimEventLoopManager(Context context, DriverStationDisplay display) {
        super(context, new NoWebServer(), null);
        this.display = display;
    }

    @Override
    public void sendTelemetryData(TelemetryMessage telemetry) {
        display.show(telemetry);
        telemetry.clearData();
    }

    private static final class NoWebServer implements EventLoopManagerClient {
        @Override
        public WebServer getWebServer() {
            throw new UnsupportedOperationException("The simulated robot controller has no robot web server");
        }

        @Override
        public OnBotJavaHelper getOnBotJavaHelper() {
            return null;
        }
    }
}
