package dev.ftcsim.hardware.device.rev;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The REV Through Bore Encoder's two outputs, as a device on the turret's shaft would give them: its absolute output, a
 * pulse whose width grows with the shaft's angle through one turn, and its incremental quadrature output, 8192 counts
 * to a turn.
 *
 * <p>The two hardware versions differ in the pulse: version 1 (REV-11-1271) sends 1 to 1024 microseconds in a period of
 * 1025, version 2 (REV-11-3174) 3.884 to 998.06 microseconds in a period of 1000 (REV's data sheet and specification
 * page). The pulse is ideal here: the real encoders vary by about 10 microseconds, which a team measures and programs.
 */
public final class ThroughBoreEncoder {

    public static final String V1 = "rev-tbe-v1";
    public static final String V2 = "rev-tbe-v2";

    /** The version a mapping that names none has. */
    public static final String USUAL = V2;

    /**
     * One version of the encoder.
     *
     * @param minPulseMicros the pulse at an angle of 0, in microseconds
     * @param maxPulseMicros the pulse at an angle of one full turn
     * @param quadratureCountsPerTurn the counts of the incremental output for one turn
     */
    public record Spec(String id, double minPulseMicros, double maxPulseMicros, int quadratureCountsPerTurn) {

        /** The pulse width for an angle given as the fraction of a turn, in [0, 1), rounded to the microsecond a counter sees. */
        public int pulseMicros(double turnFraction) {
            return (int) Math.round(minPulseMicros + turnFraction * (maxPulseMicros - minPulseMicros));
        }
    }

    private static final Map<String, Spec> SPECS = new TreeMap<>();

    static {
        SPECS.put(V1, new Spec(V1, 1.0, 1024.0, 8192));
        SPECS.put(V2, new Spec(V2, 3.884, 998.06, 8192));
    }

    private ThroughBoreEncoder() {}

    /** The ids of the versions there are. */
    public static List<String> ids() {
        return List.copyOf(SPECS.keySet());
    }

    /** The version with that id; the message lists the known ones. */
    public static Spec spec(String id) {
        Spec spec = SPECS.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("unknown encoder '" + id + "'; the known encoders are " + SPECS.keySet());
        }
        return spec;
    }
}
