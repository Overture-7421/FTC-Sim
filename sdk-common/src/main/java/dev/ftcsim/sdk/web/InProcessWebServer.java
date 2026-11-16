package dev.ftcsim.sdk.web;

import com.qualcomm.robotcore.util.WebHandlerManager;
import com.qualcomm.robotcore.util.WebServer;
import org.firstinspires.ftc.robotcore.internal.webserver.RobotControllerWebInfo;
import org.firstinspires.ftc.robotcore.internal.webserver.websockets.WebSocketManager;

/**
 * The robot controller's web server without the server: libraries register their handlers and web socket
 * namespaces exactly as they would on a robot, and the registrations are kept, but nothing ever listens on a
 * port and no client can connect. Anything a library sends to its subscribers reaches nobody.
 */
public final class InProcessWebServer implements WebServer {
    private final InProcessWebHandlerManager handlers = new InProcessWebHandlerManager(this);
    private final InProcessWebSocketManager sockets = new InProcessWebSocketManager();
    private volatile boolean started;

    @Override
    public WebHandlerManager getWebHandlerManager() {
        return handlers;
    }

    @Override
    public WebSocketManager getWebSocketManager() {
        return sockets;
    }

    @Override
    public boolean wasStarted() {
        return started;
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public RobotControllerWebInfo getConnectionInformation() {
        throw new UnsupportedOperationException("The simulated robot controller's web server does not listen, so it has no address to report");
    }
}
