package dev.ftcsim.simulator.profile;

import dev.ftcsim.physics.DcMotor;
import java.util.Map;
import java.util.TreeMap;

/** The motors a robot profile can name. */
public final class MotorCatalog {

    public static final String GOBILDA_435 = "goBILDA-5203-435";
    public static final String GOBILDA_BARE = "goBILDA-bare-6000";

    /** The bare motor's encoder: 28 counts per turn of the rotor, before any gearbox. */
    private static final double CORE_TICKS_PER_REVOLUTION = 28.0;

    /**
     * The other goBILDA 5203 Yellow Jacket gearboxes, as {nominal rpm, reduction}. They are the same motor behind a
     * different planetary gearbox, so they are the 13.7:1 motor's core scaled by their reduction, without gearbox
     * losses: stall torque goes up with the reduction and speed comes down with it.
     */
    private static final double[][] YELLOW_JACKET_GEARBOXES = {
        {1620, 3.7}, {1150, 5.2}, {312, 19.2}, {223, 26.9}, {117, 50.9}, {84, 71.2}, {60, 99.5}, {43, 139.0}, {30, 188.0},
    };

    private static final Map<String, DcMotor> MOTORS = new TreeMap<>();

    static {
        DcMotor measured = DcMotor.goBilda435();
        MOTORS.put(GOBILDA_435, measured);
        for (double[] gearbox : YELLOW_JACKET_GEARBOXES) {
            MOTORS.put("goBILDA-5203-" + (int) gearbox[0], behindGearbox(measured, gearbox[1]));
        }
        // The bare motor behind the 13.7:1 Yellow Jacket: the same winding with no gearbox.
        MOTORS.put(GOBILDA_BARE, behindGearbox(measured, 1.0));
    }

    private MotorCatalog() {}

    /** The core of {@code measured}, a motor with a gearbox, behind a gearbox of its own with the given reduction. */
    private static DcMotor behindGearbox(DcMotor measured, double reduction) {
        return new DcMotor(
                measured.stallTorqueNm() / measured.gearRatio() * reduction,
                measured.freeSpeedRadPerSec() * measured.gearRatio() / reduction,
                measured.nominalVolts(),
                measured.stallCurrentA(),
                measured.rotorInertiaKgM2(),
                reduction,
                CORE_TICKS_PER_REVOLUTION * reduction);
    }

    /** The motor of that name; the message of the exception lists the known names. */
    public static DcMotor motor(String name) {
        DcMotor motor = MOTORS.get(name);
        if (motor == null) {
            throw new IllegalArgumentException("unknown motor '" + name + "'; the known motors are " + MOTORS.keySet());
        }
        return motor;
    }
}
