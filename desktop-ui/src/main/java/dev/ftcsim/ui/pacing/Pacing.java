package dev.ftcsim.ui.pacing;

import dev.ftcsim.platform.FrameLimiter;
import dev.ftcsim.platform.FramePacer;
import java.util.Locale;

/**
 * How a render loop holds its cadence: the rate it targets and the pacer to call before each frame. The pacer waits
 * on the Windows high-resolution timer, which keeps 144 Hz within a fraction of a millisecond at a few percent of a
 * core, where the JDK's own timed waits overshoot by a millisecond or more. Belongs to the one thread that paces
 * itself with it; it is itself the {@link FramePacer} and is closed with the loop that last used it.
 */
public final class Pacing implements FramePacer, AutoCloseable {
    /** No viewport is drawn faster than this, whatever the display can do. */
    public static final int DEFAULT_CAP_HZ = 144;

    private static final double TOLERANCE_HZ = 0.5;
    private static final double MIN_DIVISOR_FRACTION = 0.75;
    private static final double FALLBACK_REFRESH_HZ = 60.0;

    private final double targetHz;
    private final FramePacer pacer;
    private final HighResolutionParker parker;

    private Pacing(double targetHz, HighResolutionParker parker) {
        this.targetHz = targetHz;
        this.parker = parker;
        this.pacer = new FrameLimiter(Math.round(1e9 / targetHz), System::nanoTime, parker);
    }

    /**
     * The rate a viewport is drawn at on a display refreshing at {@code refreshHz}, never above {@code capHz}.
     * A display at or below the cap is followed; a faster one is followed at an integer divisor of its refresh
     * when that stays close to the cap, which keeps frames evenly spaced against the display, and held at the cap
     * otherwise.
     *
     * @param refreshHz the refresh rate the display reports; zero or negative means unknown
     */
    public static double targetHz(double refreshHz, int capHz) {
        double refresh = refreshHz > 0 ? refreshHz : FALLBACK_REFRESH_HZ;
        if (refresh <= capHz + TOLERANCE_HZ) {
            return refresh;
        }
        int interval = (int) Math.ceil((refresh - TOLERANCE_HZ) / capHz);
        double divided = refresh / interval;
        return divided >= MIN_DIVISOR_FRACTION * capHz ? divided : capHz;
    }

    public static Pacing create(double refreshHz, int capHz) {
        return new Pacing(targetHz(refreshHz, capHz), new HighResolutionParker());
    }

    public double targetHz() {
        return targetHz;
    }

    public FramePacer pacer() {
        return pacer;
    }

    @Override
    public long awaitNextFrame() {
        return pacer.awaitNextFrame();
    }

    public String description() {
        return String.format(Locale.ROOT, "%s limiter at %.1f Hz", parker.description(), targetHz);
    }

    @Override
    public void close() {
        parker.close();
    }
}
