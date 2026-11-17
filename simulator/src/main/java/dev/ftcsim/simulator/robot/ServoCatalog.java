package dev.ftcsim.simulator.robot;

import dev.ftcsim.mechanisms.CrServoPlant;
import java.util.Map;
import java.util.TreeMap;

/**
 * The continuous-rotation servos a mapping can name, with the no-load speed each has on a hub's 5 V servo port.
 *
 * <p>The speeds come from the makers' speed per 60 degrees at 4.8 V and 6.0 V, interpolated to 5 V (the Axon Mini+ takes
 * 0.110 s and 0.090 s; the Axon MAX 0.140 s and 0.115 s; the goBILDA 25-2 turns 40 and 50 rpm). They are the servo's
 * positional speed, which is what its continuous mode is taken to reach at full command. The lag is an estimate.
 */
public final class ServoCatalog {

    public static final String AXON_MINI_PLUS = "axon-mini-plus";
    public static final String AXON_MAX = "axon-max";
    public static final String GOBILDA_TORQUE = "gobilda-2000-torque";

    /** The usual servo of a mapping. */
    public static final String USUAL = AXON_MINI_PLUS;

    /** The time constant of every servo's speed response, estimated. */
    static final double LAG_SECONDS = 0.03;

    /** One servo: its no-load speed on a 5 V port. */
    record Model(String id, double noLoadRadPerSec) {}

    private static final Map<String, Model> MODELS = new TreeMap<>();

    static {
        MODELS.put(AXON_MINI_PLUS, new Model(AXON_MINI_PLUS, fromSecondsPer60Degrees(0.110, 0.090)));
        MODELS.put(AXON_MAX, new Model(AXON_MAX, fromSecondsPer60Degrees(0.140, 0.115)));
        MODELS.put(GOBILDA_TORQUE, new Model(GOBILDA_TORQUE, fromRpm(40.0, 50.0)));
    }

    private ServoCatalog() {}

    private static double atFiveVolts(double at4v8, double at6v0) {
        return at4v8 + (at6v0 - at4v8) * (5.0 - 4.8) / (6.0 - 4.8);
    }

    private static double fromSecondsPer60Degrees(double at4v8, double at6v0) {
        return Math.toRadians(60.0) / atFiveVolts(at4v8, at6v0);
    }

    private static double fromRpm(double at4v8, double at6v0) {
        return atFiveVolts(at4v8, at6v0) * 2.0 * Math.PI / 60.0;
    }

    /** The no-load speed of the named servo at full command, in rad/s; the message lists the known names. */
    public static double noLoadRadPerSec(String id) {
        return model(id).noLoadRadPerSec();
    }

    /** A plant for the named servo. */
    public static CrServoPlant plant(String id) {
        return new CrServoPlant(noLoadRadPerSec(id), LAG_SECONDS);
    }

    private static Model model(String id) {
        Model model = MODELS.get(id);
        if (model == null) {
            throw new IllegalArgumentException("unknown servo '" + id + "'; the known servos are " + MODELS.keySet());
        }
        return model;
    }
}
