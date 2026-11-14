package dev.ftcsim.contracts.robot;

/** Where the robot controller is in the life of an OpMode. */
public enum RobotPhase {
    /** No driver OpMode is selected; the controller idles on the SDK's built-in default. */
    IDLE,
    /** The selected OpMode has been initialised and is waiting for Start. */
    INIT,
    /** The OpMode has been started and has not been told to stop. */
    RUNNING
}
