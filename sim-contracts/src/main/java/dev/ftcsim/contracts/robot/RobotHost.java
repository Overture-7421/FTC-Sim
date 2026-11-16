package dev.ftcsim.contracts.robot;

import java.util.List;

/**
 * The simulator's view of a robot program running inside the SDK world. The simulator and the SDK live in
 * different class loaders, so everything crossing this interface is a plain value; no SDK type ever leaks out.
 *
 * <p>The methods mirror what a Driver Station can do: choose an OpMode, press Init, press Start, press Stop.
 * Calls are made from the simulator's thread, one at a time.
 */
public interface RobotHost extends AutoCloseable {

    /** The OpModes the program declares, in a stable order. */
    List<OpModeDescriptor> opModes();

    /** Presses Init: selects the named OpMode and runs its initialisation. */
    void init(String opModeName);

    /** Presses Start on the initialised OpMode. */
    void start();

    /** Presses Stop and returns once the OpMode has finished and the controller is idle again. */
    void stop();

    /**
     * One pass of the robot controller's event loop, which is how gamepad input, state changes and telemetry
     * reach the OpMode. The caller decides when, and in simulated time, each pass happens.
     */
    void eventLoop(GamepadState gamepad1, GamepadState gamepad2);

    RobotPhase phase();

    TelemetrySnapshot telemetry();

    /** Clears what the Driver Station screen shows: the telemetry lines, the warning and the error, such as a crash. */
    default void clearMessages() {}

    @Override
    void close();
}
