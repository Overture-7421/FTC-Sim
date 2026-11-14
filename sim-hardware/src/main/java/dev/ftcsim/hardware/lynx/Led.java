package dev.ftcsim.hardware.lynx;

/** The status LED of a hub: the colour last set and the blink pattern last set, kept as the wire carries them. */
final class Led {

    static final int STEP_BYTES = 4;
    static final int MAX_PATTERN_BYTES = 16 * STEP_BYTES;

    private byte[] color = new byte[3];
    private byte[] pattern = new byte[STEP_BYTES];

    byte[] color() {
        return color.clone();
    }

    void setColor(byte[] rgb) {
        color = rgb.clone();
    }

    /** The pattern as steps of duration in tenths of a second and blue, green and red; it ends in a null step. */
    byte[] pattern() {
        return pattern.clone();
    }

    void setPattern(byte[] steps) {
        pattern = steps.clone();
    }

    /** The hub forgets its LED when the host falls silent for too long. */
    void reset() {
        color = new byte[3];
        pattern = new byte[STEP_BYTES];
    }
}
