package dev.ftcsim.contracts.session;

import java.util.List;

/**
 * What is known about the robot before anything runs, for drawing it and for the panels that list its hardware.
 *
 * @param lengthM  the chassis box, drawn centred on the robot's position
 * @param motors   the names of the motors the robot's design wires, in the design's order
 * @param servos   the names of the servos the design wires
 * @param devices  the names of the I2C devices the design wires
 * @param model    the id of the picture the window draws the robot with, or empty for a box of the chassis's size
 */
public record RobotInfo(
        String name, double lengthM, double widthM, double heightM, List<String> motors, List<String> servos, List<String> devices,
        String model) {

    /** The picture of the BIOBUZZ robot: a chassis with a turret on it, which is a body of its own in the snapshot. */
    public static final String BIOBUZZ_MODEL = "biobuzz";

    public RobotInfo {
        motors = List.copyOf(motors);
        servos = List.copyOf(servos);
        devices = List.copyOf(devices);
        model = model == null ? "" : model;
    }

    /** A robot drawn as a box. */
    public RobotInfo(
            String name, double lengthM, double widthM, double heightM, List<String> motors, List<String> servos, List<String> devices) {
        this(name, lengthM, widthM, heightM, motors, servos, devices, "");
    }
}
