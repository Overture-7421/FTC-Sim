package dev.ftcsim.simulator.robot;

import dev.ftcsim.contracts.session.MotorChoice;
import dev.ftcsim.physics.DcMotor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The motors a mapping can name. */
public final class MotorCatalog {

    public static final String GOBILDA_435 = "goBILDA-5203-435";
    public static final String GOBILDA_BARE = "goBILDA-bare-6000";
    /** The SWYFT Spike 550-class motor behind a 12.7:1 reduction to the wheel, the BIOBUZZ robot's drive. */
    public static final String SWYFT_SPIKE_12_7 = "swyft-spike-12.7";

    /** The Spike's published numbers at 12 V, before any gearbox: 6000 rpm, 1.47 kg cm of stall torque, 9.2 A at stall, a 28 PPR encoder. */
    private static final double SPIKE_STALL_TORQUE_NM = 1.47 * 0.0980665;
    private static final double SPIKE_FREE_SPEED_RAD_PER_SEC = 6000.0 * 2.0 * Math.PI / 60.0;
    private static final double SPIKE_STALL_CURRENT_A = 9.2;
    private static final double SPIKE_DRIVE_REDUCTION = 12.7;

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
        MOTORS.put(SWYFT_SPIKE_12_7, new DcMotor(
                SPIKE_STALL_TORQUE_NM * SPIKE_DRIVE_REDUCTION, SPIKE_FREE_SPEED_RAD_PER_SEC / SPIKE_DRIVE_REDUCTION,
                measured.nominalVolts(), SPIKE_STALL_CURRENT_A, measured.rotorInertiaKgM2(), SPIKE_DRIVE_REDUCTION,
                CORE_TICKS_PER_REVOLUTION * SPIKE_DRIVE_REDUCTION));
    }

    private MotorCatalog() {}

    /**
     * What a window offers for the drivetrain: every catalogue motor with a name for people, the measured 435 rpm
     * one first because it is the default, then the others from the fastest, the bare motor last.
     */
    public static List<MotorChoice> choices() {
        List<MotorChoice> choices = new ArrayList<>();
        choices.add(new MotorChoice(GOBILDA_435, "goBILDA 5203 Yellow Jacket, 435 rpm"));
        double[][] byRpm = YELLOW_JACKET_GEARBOXES.clone();
        Arrays.sort(byRpm, Comparator.comparingDouble((double[] gearbox) -> gearbox[0]).reversed());
        for (double[] gearbox : byRpm) {
            int rpm = (int) gearbox[0];
            choices.add(new MotorChoice("goBILDA-5203-" + rpm, "goBILDA 5203 Yellow Jacket, " + rpm + " rpm"));
        }
        choices.add(new MotorChoice(GOBILDA_BARE, "goBILDA bare motor, no gearbox"));
        choices.add(new MotorChoice(SWYFT_SPIKE_12_7, "SWYFT Spike 550 motor, 12.7:1 to the wheel"));
        return List.copyOf(choices);
    }

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
