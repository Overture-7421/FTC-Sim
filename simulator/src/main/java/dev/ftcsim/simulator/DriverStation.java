package dev.ftcsim.simulator;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.RobotHost;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.core.kernel.SimulationKernel;
import java.util.List;

/**
 * The Driver Station's part of a run: choose an OpMode, press Init, Start and Stop. A press happens at the
 * simulated instant the caller is at, whether that is between two slices of time or inside a scheduled callback.
 */
final class DriverStation {

    /** How long in real time the robot thread may take to park after a press; simulated time does not pass. */
    static final long SETTLE_MILLIS = 10_000;

    private final SimulationKernel kernel;
    private final RobotHost host;

    DriverStation(SimulationKernel kernel, RobotHost host) {
        this.kernel = kernel;
        this.host = host;
    }

    List<OpModeDescriptor> opModes() {
        return host.opModes();
    }

    RobotPhase phase() {
        return host.phase();
    }

    void init(String opMode) {
        kernel.runAsEvent(() -> {
            host.init(opMode);
            kernel.settleRobot(SETTLE_MILLIS);
        });
    }

    void start() {
        kernel.runAsEvent(() -> {
            host.start();
            kernel.settleRobot(SETTLE_MILLIS);
        });
    }

    void stop() {
        kernel.runAsEvent(host::stop);
    }
}
