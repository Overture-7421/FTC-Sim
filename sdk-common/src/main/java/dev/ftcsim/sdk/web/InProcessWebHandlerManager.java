package dev.ftcsim.sdk.web;

import com.qualcomm.robotcore.util.WebHandlerManager;
import com.qualcomm.robotcore.util.WebServer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler;
import org.firstinspires.ftc.robotcore.internal.webserver.WebObserver;

/** Keeps the handlers that libraries register with the robot controller's web server. */
final class InProcessWebHandlerManager implements WebHandlerManager {
    private final WebServer server;
    private final Map<String, WebHandler> handlers = new LinkedHashMap<>();

    InProcessWebHandlerManager(WebServer server) {
        this.server = server;
    }

    @Override
    public WebServer getWebServer() {
        return server;
    }

    @Override
    public synchronized void register(String command, WebHandler webHandler) {
        handlers.put(command, webHandler);
    }

    @Override
    public synchronized WebHandler getRegistered(String command) {
        return handlers.get(command);
    }

    /** Observers see client requests, and a server without clients receives none. */
    @Override
    public void registerObserver(String key, WebObserver webObserver) {}
}
