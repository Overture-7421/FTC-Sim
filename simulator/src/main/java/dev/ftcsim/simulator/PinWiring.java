package dev.ftcsim.simulator;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.hardware.lynx.AnalogInput;
import dev.ftcsim.hardware.lynx.DigitalInput;
import dev.ftcsim.hardware.lynx.LynxHub;
import dev.ftcsim.hardware.pin.PinSources;
import dev.ftcsim.hardware.wiring.WiringTable;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.util.Map;
import java.util.function.Function;

/**
 * The digital and analog pins a robot profile lists: where robot code finds each one, and what is plugged into it on
 * its hub. A pin the profile lists without a source is connected but idle, so it reads high through the hub's pull-up,
 * or 0 V, and is not reported as unbound.
 */
final class PinWiring {

    private PinWiring() {}

    /** Tells the wiring table which pin each name robot code asks for is. */
    static void place(RobotProfile profile, int parent, WiringTable.Builder wiring) {
        profile.digitals().forEach((name, pin) -> wiring.digital(name, hubOf(pin, parent), pin.channel()));
        profile.analogs().forEach((name, pin) -> wiring.analog(name, hubOf(pin, parent), pin.channel()));
    }

    /**
     * Plugs the source of each pin into its port. A source that follows a motor reads the encoder {@code encoders}
     * gives for the motor's name, at the moment the hub reads the pin.
     */
    static void connect(RobotProfile profile, int parent, Map<Integer, LynxHub> hubs, Function<String, EncoderSource> encoders) {
        profile.digitals().forEach((name, pin) -> hubs.get(hubOf(pin, parent))
                .digitalPort(pin.channel())
                .connect(digitalSource("digitals." + name + ".source", pin.source(), encoders)));
        profile.analogs().forEach((name, pin) -> hubs.get(hubOf(pin, parent))
                .analogPort(pin.channel())
                .connect(analogSource("analogs." + name + ".source", pin.source(), encoders)));
    }

    private static int hubOf(RobotProfile.Pin pin, int parent) {
        return pin.hub() == null ? parent : pin.hub();
    }

    private static DigitalInput digitalSource(String where, RobotProfile.DigitalSource source, Function<String, EncoderSource> encoders) {
        if (source == null) {
            return PinSources.level(true);
        }
        return switch (source.type()) {
            case RobotProfile.CONSTANT_SOURCE -> PinSources.level(RobotProfileLoader.isHigh(where + ".level", source.level()));
            case RobotProfile.ENCODER_SOURCE -> PinSources.limitSwitch(
                    encoders.apply(source.motor()), source.counts(), RobotProfileLoader.closedWhen(where + ".closedWhen", source.closedWhen()));
            default -> throw new IllegalStateException("a validated profile has no source of type '" + source.type() + "' at " + where);
        };
    }

    private static AnalogInput analogSource(String where, RobotProfile.AnalogSource source, Function<String, EncoderSource> encoders) {
        if (source == null) {
            return PinSources.volts(0.0);
        }
        return switch (source.type()) {
            case RobotProfile.CONSTANT_SOURCE -> PinSources.volts(source.volts());
            case RobotProfile.ENCODER_SOURCE ->
                PinSources.potentiometer(encoders.apply(source.motor()), source.voltsAtZero(), source.voltsPerCount());
            default -> throw new IllegalStateException("a validated profile has no source of type '" + source.type() + "' at " + where);
        };
    }
}
