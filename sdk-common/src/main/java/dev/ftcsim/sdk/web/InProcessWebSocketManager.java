package dev.ftcsim.sdk.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.firstinspires.ftc.robotcore.internal.webserver.websockets.FtcWebSocket;
import org.firstinspires.ftc.robotcore.internal.webserver.websockets.FtcWebSocketMessage;
import org.firstinspires.ftc.robotcore.internal.webserver.websockets.WebSocketManager;
import org.firstinspires.ftc.robotcore.internal.webserver.websockets.WebSocketNamespaceHandler;

/** The web socket namespaces of a server that has no clients: registering works, and broadcasting reaches nobody. */
final class InProcessWebSocketManager implements WebSocketManager {
    private final Set<String> namespaces = new LinkedHashSet<>();

    @Override
    public synchronized void registerNamespaceHandler(WebSocketNamespaceHandler handler) {
        register(handler.getNamespace());
    }

    @Override
    public synchronized void registerNamespaceAsBroadcastOnly(String namespace) {
        register(namespace);
    }

    private void register(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("The namespace is empty");
        }
        if (SYSTEM_NAMESPACE.equals(namespace)) {
            throw new IllegalArgumentException("The namespace '" + namespace + "' is reserved");
        }
        if (!namespaces.add(namespace)) {
            throw new IllegalArgumentException("The namespace '" + namespace + "' is already registered");
        }
    }

    @Override
    public synchronized int broadcastToNamespace(String namespace, FtcWebSocketMessage message) {
        if (!namespace.equals(message.getNamespace())) {
            throw new IllegalArgumentException(
                    "The message belongs to namespace '" + message.getNamespace() + "', not to '" + namespace + "'");
        }
        requireRegistered(namespace);
        return 0;
    }

    @Override
    public synchronized boolean webSocketIsSubscribedToNamespace(String namespace, FtcWebSocket webSocket) {
        requireRegistered(namespace);
        return false;
    }

    private void requireRegistered(String namespace) {
        if (!namespaces.contains(namespace)) {
            throw new IllegalStateException("The namespace '" + namespace + "' is not registered");
        }
    }

    synchronized List<String> namespaces() {
        return new ArrayList<>(namespaces);
    }
}
