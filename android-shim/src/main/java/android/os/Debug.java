package android.os;

import java.lang.management.ManagementFactory;

/** Debugging support; only the questions the SDK asks about an attached debugger are answered. */
public final class Debug {
    private static final boolean DEBUG_AGENT = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .anyMatch(argument -> argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp"));

    private Debug() {}

    /** A JVM started with a debug agent is the desktop's "debugger connected": single-stepping must not trip the SDK's stuck-code timers. */
    public static boolean isDebuggerConnected() {
        return DEBUG_AGENT;
    }

    public static boolean waitingForDebugger() {
        return false;
    }
}
