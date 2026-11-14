package android.os;

/**
 * Time since the simulated controller started. Everything derives from {@link System#nanoTime()}, which in a
 * robot world is the simulator's clock, so these readings follow simulated time.
 */
public final class SystemClock {
    private static final long BOOT_NANOS = System.nanoTime();

    private SystemClock() {}

    public static long uptimeMillis() {
        return elapsedRealtimeNanos() / 1_000_000L;
    }

    public static long elapsedRealtime() {
        return uptimeMillis();
    }

    public static long elapsedRealtimeNanos() {
        return System.nanoTime() - BOOT_NANOS;
    }

    /** Sleeps for at least the given time and defers any interrupt until the sleep is over. */
    public static void sleep(long millis) {
        long deadline = uptimeMillis() + millis;
        boolean interrupted = false;
        for (long remaining = millis; remaining > 0; remaining = deadline - uptimeMillis()) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
