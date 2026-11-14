package android.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Android's logger, routed to a {@link Sink} installed by the host. Until a host installs one, lines are
 * written to standard error so that nothing is lost.
 */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    /** Receives every log line the SDK and robot libraries emit. */
    public interface Sink {
        void log(int priority, String tag, String message);
    }

    private static volatile Sink sink = (priority, tag, message) ->
            System.err.println(priorityLetter(priority) + "/" + tag + ": " + message);

    private Log() {}

    /** Installs the host's sink; the shim stays free of any dependency on the simulator itself. */
    public static void setSink(Sink newSink) {
        sink = java.util.Objects.requireNonNull(newSink, "sink");
    }

    /** The sink in use, for a host that wants to observe the log without taking it over. */
    public static Sink sink() {
        return sink;
    }

    public static int println(int priority, String tag, String message) {
        sink.log(priority, tag, message);
        return message == null ? 0 : message.length();
    }

    public static int v(String tag, String message) {
        return println(VERBOSE, tag, message);
    }

    public static int v(String tag, String message, Throwable error) {
        return println(VERBOSE, tag, message + '\n' + getStackTraceString(error));
    }

    public static int d(String tag, String message) {
        return println(DEBUG, tag, message);
    }

    public static int d(String tag, String message, Throwable error) {
        return println(DEBUG, tag, message + '\n' + getStackTraceString(error));
    }

    public static int i(String tag, String message) {
        return println(INFO, tag, message);
    }

    public static int i(String tag, String message, Throwable error) {
        return println(INFO, tag, message + '\n' + getStackTraceString(error));
    }

    public static int w(String tag, String message) {
        return println(WARN, tag, message);
    }

    public static int w(String tag, String message, Throwable error) {
        return println(WARN, tag, message + '\n' + getStackTraceString(error));
    }

    public static int w(String tag, Throwable error) {
        return println(WARN, tag, getStackTraceString(error));
    }

    public static int e(String tag, String message) {
        return println(ERROR, tag, message);
    }

    public static int e(String tag, String message, Throwable error) {
        return println(ERROR, tag, message + '\n' + getStackTraceString(error));
    }

    public static boolean isLoggable(String tag, int priority) {
        return true;
    }

    public static String getStackTraceString(Throwable error) {
        if (error == null) {
            return "";
        }
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static char priorityLetter(int priority) {
        return switch (priority) {
            case VERBOSE -> 'V';
            case DEBUG -> 'D';
            case INFO -> 'I';
            case WARN -> 'W';
            case ERROR -> 'E';
            default -> 'A';
        };
    }
}
