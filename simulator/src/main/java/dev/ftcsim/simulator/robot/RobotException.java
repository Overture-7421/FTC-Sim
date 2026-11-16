package dev.ftcsim.simulator.robot;

/** A robot that cannot be built from what it was given; the message says what is wrong. */
public final class RobotException extends IllegalArgumentException {

    public RobotException(String message) {
        super(message);
    }

    public RobotException(String message, Throwable cause) {
        super(message, cause);
    }
}
