package dev.ftcsim.simulator.robot;

import dev.ftcsim.mechanisms.CrServoPlant;
import dev.ftcsim.mechanisms.PositionServoPlant;
import java.util.Map;
import java.util.TreeMap;

/**
 * The servos of the two robots, with the no-load speed each has on a hub's 5 V servo port.
 *
 * <p>The speeds come from the makers' speed per 60 degrees at 4.8 V and 6.0 V, interpolated to 5 V (the Axon Mini+ takes
 * 0.110 s and 0.090 s; the Super Speed 0.055 s and 0.043 s; the Torque servo turns 40 and 50 rpm). They are the servo's
 * positional speed, which is what its continuous mode is taken to reach at full command. The lag is an estimate.
 */
public final class ServoCatalog {

    public static final String AXON_MINI_PLUS = "axon-mini-plus";
    /** The goBILDA Torque servo with five turns of travel (1800 degrees): BIOBUZZ's colibri arms and stopper. */
    public static final String GOBILDA_TORQUE_5_TURN = "gobilda-2000-torque-5turn";
    /** The goBILDA Dual Mode Super Speed servo (2000-0025-0004), 300 degrees: BIOBUZZ's hood. */
    public static final String GOBILDA_SUPER_SPEED = "gobilda-2000-superspeed";

    /** The time constant of every servo's speed response, estimated. */
    static final double LAG_SECONDS = 0.03;

    /** One servo: its no-load speed on a 5 V port and, for a positional one, the travel from position 0 to 1. */
    record Model(String id, double noLoadRadPerSec, double travelRadians) {}

    private static final Map<String, Model> MODELS = new TreeMap<>();

    static {
        double turn = Math.toRadians(300.0);
        MODELS.put(AXON_MINI_PLUS, new Model(AXON_MINI_PLUS, fromSecondsPer60Degrees(0.110, 0.090), turn));
        MODELS.put(GOBILDA_SUPER_SPEED, new Model(GOBILDA_SUPER_SPEED, fromSecondsPer60Degrees(0.055, 0.043), turn));
        MODELS.put(GOBILDA_TORQUE_5_TURN, new Model(GOBILDA_TORQUE_5_TURN, fromRpm(40.0, 50.0), Math.toRadians(1800.0)));
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

    /** A plant for the named continuous-rotation servo. */
    public static CrServoPlant plant(String id) {
        return new CrServoPlant(noLoadRadPerSec(id), LAG_SECONDS);
    }

    /** A plant for the named servo used as a positional one, mounted {@code mirrored} or not. */
    public static PositionServoPlant positionPlant(String id, boolean mirrored) {
        return new PositionServoPlant(noLoadRadPerSec(id), mirrored, model(id).travelRadians());
    }

    private static Model model(String id) {
        Model model = MODELS.get(id);
        if (model == null) {
            throw new IllegalArgumentException("unknown servo '" + id + "'; the known servos are " + MODELS.keySet());
        }
        return model;
    }
}

