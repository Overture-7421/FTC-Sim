package dev.ftcsim.hardware.lynx;

import java.util.Arrays;

/**
 * A group of hobby-servo style pulse outputs: a frame period, a pulse width and an enable per channel. The hub
 * only stores them; the host reads what the outputs would be doing.
 */
final class PulseBank {

    private final int minFramePeriod;
    private final int minPulseWidth;
    private final boolean configureBeforeEnable;
    private final int[] framePeriod;
    private final int[] pulseWidth;
    private final boolean[] enabled;
    private final boolean[] configured;

    PulseBank(int count, int minFramePeriod, int minPulseWidth, boolean configureBeforeEnable) {
        this.minFramePeriod = minFramePeriod;
        this.minPulseWidth = minPulseWidth;
        this.configureBeforeEnable = configureBeforeEnable;
        this.framePeriod = new int[count];
        this.pulseWidth = new int[count];
        this.enabled = new boolean[count];
        this.configured = new boolean[count];
    }

    int count() {
        return enabled.length;
    }

    int minFramePeriod() {
        return minFramePeriod;
    }

    int minPulseWidth() {
        return minPulseWidth;
    }

    int framePeriod(int channel) {
        return framePeriod[channel];
    }

    int pulseWidth(int channel) {
        return pulseWidth[channel];
    }

    boolean enabled(int channel) {
        return enabled[channel];
    }

    int enabledCount() {
        int count = 0;
        for (boolean on : enabled) {
            if (on) {
                count++;
            }
        }
        return count;
    }

    void setFramePeriod(int channel, int micros) {
        framePeriod[channel] = micros;
        configured[channel] = true;
    }

    void setPulseWidth(int channel, int micros) {
        pulseWidth[channel] = micros;
    }

    /** A servo that has never been given a frame period cannot be switched on. */
    void enable(int channel, boolean on) {
        if (on && configureBeforeEnable && !configured[channel]) {
            throw new CommandRejected(NackReason.SERVO_NOT_CONFIG_BEFORE_ENABLED);
        }
        enabled[channel] = on;
    }

    void disableAll() {
        Arrays.fill(enabled, false);
    }
}
