package dev.ftcsim.hardware.pin;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.hardware.lynx.AnalogInput;
import dev.ftcsim.hardware.lynx.DigitalInput;
import java.util.Objects;

/**
 * What can be plugged into the digital and analog pins of a hub: a pin held at a level, a limit switch that closes
 * at a position of a mechanism, a potentiometer turned by one. Each is a plain function of what the mechanism's
 * encoder counts at the moment the hub reads the pin, so there is nothing to step and nothing that depends on how
 * time is sliced.
 *
 * <p>An encoder here is the physical shaft: the count since the plant was created, which robot code cannot reset
 * (a motor's {@code STOP_AND_RESET_ENCODER} only moves the hub's own offset). A switch bolted to a slide closes where
 * the slide is, whatever the program last called zero.
 *
 * <p>Not modelled: contact bounce, hysteresis, switching delay, wiper noise and the potentiometer's own wear.
 */
public final class PinSources {

    /** Which side of its threshold an encoder count closes a switch. */
    public enum Side {
        ABOVE,
        BELOW
    }

    private PinSources() {}

    /** A pin held at a logic level: {@code true} is high. */
    public static DigitalInput level(boolean high) {
        return nanos -> high;
    }

    /**
     * A normally open switch to ground, wired as a REV touch sensor or a magnetic limit switch is: the hub's pull-up
     * holds the pin high, and it reads low while the switch is closed. The switch is closed while the count is
     * strictly on the {@code closedWhen} side of {@code thresholdCounts}, so at the threshold itself it is open.
     */
    public static DigitalInput limitSwitch(EncoderSource encoder, double thresholdCounts, Side closedWhen) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(closedWhen, "closedWhen");
        requireFinite(thresholdCounts, "thresholdCounts");
        return nanos -> {
            long counts = encoder.encoderCounts();
            boolean closed = closedWhen == Side.ABOVE ? counts > thresholdCounts : counts < thresholdCounts;
            return !closed;
        };
    }

    /** A pin held at a voltage. */
    public static AnalogInput volts(double volts) {
        requireFinite(volts, "volts");
        return nanos -> volts;
    }

    /**
     * A potentiometer on a mechanism: {@code voltsAtZero} when the encoder reads zero, changing by
     * {@code voltsPerCount} for every count, and never beyond the 0 to {@link AnalogInput#FULL_SCALE_VOLTS} V its
     * supply allows. A negative slope is a potentiometer wired the other way round.
     */
    public static AnalogInput potentiometer(EncoderSource encoder, double voltsAtZero, double voltsPerCount) {
        Objects.requireNonNull(encoder, "encoder");
        requireFinite(voltsAtZero, "voltsAtZero");
        requireFinite(voltsPerCount, "voltsPerCount");
        return nanos -> Math.max(0.0, Math.min(AnalogInput.FULL_SCALE_VOLTS, voltsAtZero + voltsPerCount * encoder.encoderCounts()));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number: " + value);
        }
    }
}
