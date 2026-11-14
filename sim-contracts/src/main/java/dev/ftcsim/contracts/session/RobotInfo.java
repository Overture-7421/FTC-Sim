package dev.ftcsim.contracts.session;

import java.util.List;

/**
 * What is known about the robot before anything runs, for drawing it and for the panels that list its hardware.
 *
 * @param lengthM  the chassis box, drawn centred on the robot's position
 * @param motors   the names of the motors the profile wires, in profile order
 * @param servos   the names of the servos the profile wires
 * @param devices  the names of the I2C devices the profile wires
 */
public record RobotInfo(
        String name, double lengthM, double widthM, double heightM, List<String> motors, List<String> servos, List<String> devices) {

    public RobotInfo {
        motors = List.copyOf(motors);
        servos = List.copyOf(servos);
        devices = List.copyOf(devices);
    }
}
