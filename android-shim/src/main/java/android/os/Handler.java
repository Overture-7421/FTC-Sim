package android.os;

import java.util.Objects;

/**
 * Dispatches {@link Runnable} tasks to a {@link Looper}. The simulated controller has no separate UI thread, so
 * {@link #post(Runnable)} runs the action immediately on the calling thread, matching {@link android.app.Activity#runOnUiThread}.
 */
public class Handler {
    private final Looper looper;

    public Handler() {
        this(Looper.getMainLooper());
    }

    public Handler(Looper looper) {
        this.looper = Objects.requireNonNull(looper, "looper");
    }

    public final Looper getLooper() {
        return looper;
    }

    public final boolean post(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable").run();
        return true;
    }
}
