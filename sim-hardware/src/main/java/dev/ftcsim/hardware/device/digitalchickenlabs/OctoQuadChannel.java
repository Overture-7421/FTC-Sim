package dev.ftcsim.hardware.device.digitalchickenlabs;

import dev.ftcsim.contracts.hardware.EncoderSource;

/**
 * One of the OctoQuad's eight channels: a connector that either counts the steps of a quadrature encoder or measures
 * the pulse width of an absolute encoder, with the parameters robot code can set on it. Which of the two it is
 * belongs to the bank the channel is in, so the methods that depend on it are told.
 *
 * <p>A quadrature channel counts what its source counts, from the last reset, with the channel's direction applied.
 * A pulse-width channel turns the source into the absolute encoder's rotation: the pulse is 1 to 1024 microseconds
 * (as a REV Through Bore Encoder's is) over one turn of {@code countsPerRevolution} source counts, and robot code's
 * minimum and maximum widths only say how wide a turn is when the firmware accumulates turns or measures velocity.
 */
final class OctoQuadChannel {

    static final int DEFAULT_VELOCITY_INTERVAL_MS = 50;
    static final int PHYSICAL_MIN_PULSE_US = 1;
    static final int PHYSICAL_MAX_PULSE_US = 1024;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final EncoderSource source;
    private final int countsPerRevolution;

    private boolean reversed;
    private long countsBase;
    private long wrapsBase;
    private int velocityIntervalMs = DEFAULT_VELOCITY_INTERVAL_MS;
    private int minPulseUs = PHYSICAL_MIN_PULSE_US;
    private int maxPulseUs = PHYSICAL_MAX_PULSE_US;
    private boolean wrapTracking;

    private long lastSampleNanos;
    private long nextSampleNanos;
    private double lastSampleValue;
    private int velocity;

    /**
     * @param source              what the connector is wired to, or null if nothing is
     * @param countsPerRevolution how many source counts one turn of an absolute encoder is, or 0 if it is not one
     */
    OctoQuadChannel(EncoderSource source, int countsPerRevolution) {
        this.source = source;
        this.countsPerRevolution = countsPerRevolution;
    }

    long rawCounts() {
        return source == null ? 0 : source.encoderCounts();
    }

    boolean reversed() {
        return reversed;
    }

    boolean wrapTracking() {
        return wrapTracking;
    }

    int velocityIntervalMs() {
        return velocityIntervalMs;
    }

    int minPulseUs() {
        return minPulseUs;
    }

    int maxPulseUs() {
        return maxPulseUs;
    }

    /** What the position register holds: the signed count since the last reset, or the pulse width in microseconds. */
    long position(boolean pulseWidth) {
        if (!pulseWidth) {
            return sign() * (rawCounts() - countsBase);
        }
        if (countsPerRevolution == 0) {
            return 0;
        }
        double turns = turns();
        long wholeTurns = (long) Math.floor(turns);
        double width = PHYSICAL_MAX_PULSE_US - PHYSICAL_MIN_PULSE_US;
        long pulse = Math.round(PHYSICAL_MIN_PULSE_US + (turns - wholeTurns) * width);
        return wrapTracking ? pulse + (wholeTurns - wrapsBase) * (maxPulseUs - minPulseUs) : pulse;
    }

    /** What the velocity register holds: the change of the position during the last sampling interval. */
    int velocity() {
        return velocity;
    }

    void setReversed(boolean reversed, boolean pulseWidth, long nowNanos) {
        long position = position(false);
        this.reversed = reversed;
        // The count carries on from where it was; only the steps still to come go the other way.
        countsBase = rawCounts() - sign() * position;
        wrapsBase = (long) Math.floor(turns());
        restartSampling(nowNanos, pulseWidth);
    }

    void setWrapTracking(boolean wrapTracking) {
        this.wrapTracking = wrapTracking;
    }

    void setPulseRange(int minPulseUs, int maxPulseUs) {
        this.minPulseUs = minPulseUs;
        this.maxPulseUs = maxPulseUs;
    }

    void setVelocityInterval(int milliseconds, long nowNanos, boolean pulseWidth) {
        velocityIntervalMs = milliseconds;
        restartSampling(nowNanos, pulseWidth);
    }

    /** Zeroes the count; a wrap-tracking pulse-width channel keeps its absolute pulse and forgets its turns. */
    void reset() {
        countsBase = rawCounts();
        wrapsBase = (long) Math.floor(turns());
    }

    /** The channel as it is after the chip's own reset: counting forwards with the factory parameters. */
    void restoreDefaults() {
        reversed = false;
        velocityIntervalMs = DEFAULT_VELOCITY_INTERVAL_MS;
        minPulseUs = PHYSICAL_MIN_PULSE_US;
        maxPulseUs = PHYSICAL_MAX_PULSE_US;
        wrapTracking = false;
        reset();
    }

    /** Starts the velocity measurement afresh from the present position. */
    void restartSampling(long nowNanos, boolean pulseWidth) {
        lastSampleNanos = nowNanos;
        nextSampleNanos = nowNanos + velocityIntervalMs * NANOS_PER_MILLI;
        lastSampleValue = continuous(pulseWidth);
        velocity = 0;
    }

    /** Completes the velocity measurement if its interval has run out by {@code nowNanos}. */
    void sample(long nowNanos, boolean pulseWidth) {
        if (nowNanos < nextSampleNanos) {
            return;
        }
        double value = continuous(pulseWidth);
        double elapsedMs = (nowNanos - lastSampleNanos) / (double) NANOS_PER_MILLI;
        long change = Math.round((value - lastSampleValue) * velocityIntervalMs / elapsedMs);
        velocity = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, change));
        lastSampleValue = value;
        lastSampleNanos = nowNanos;
        long interval = velocityIntervalMs * NANOS_PER_MILLI;
        nextSampleNanos += ((nowNanos - nextSampleNanos) / interval + 1) * interval;
    }

    /** The position without the wrap-around of a pulse, in the unit the velocity is measured in. */
    private double continuous(boolean pulseWidth) {
        if (!pulseWidth) {
            return sign() * (double) rawCounts();
        }
        return countsPerRevolution == 0 ? 0 : turns() * (maxPulseUs - minPulseUs);
    }

    private double turns() {
        return countsPerRevolution == 0 ? 0 : sign() * (double) rawCounts() / countsPerRevolution;
    }

    private int sign() {
        return reversed ? -1 : 1;
    }
}
