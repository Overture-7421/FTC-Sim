package android.os;

/**
 * Message loop for a thread. The simulated controller has no separate Android main-looper thread, so
 * {@link #getMainLooper()} returns a single placeholder that {@link Handler} accepts.
 */
public final class Looper {
    private static final Looper MAIN = new Looper();

    private Looper() {}

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return MAIN;
    }
}
