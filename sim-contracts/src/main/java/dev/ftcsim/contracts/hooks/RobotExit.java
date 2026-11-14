package dev.ftcsim.contracts.hooks;

/**
 * What {@code System.exit}, {@code Runtime.exit} and {@code Runtime.halt} do in a robot program: the simulator's
 * process is not the robot's to end, so the call ends the calling thread by unwinding it, and the host learns of it.
 */
public final class RobotExit extends RobotUnwind {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final boolean halt;

    public RobotExit(int status, boolean halt) {
        super("the robot program called " + (halt ? "Runtime.halt(" : "System.exit(") + status + ")");
        this.status = status;
        this.halt = halt;
    }

    public int status() {
        return status;
    }

    /** Whether it was {@code Runtime.halt}, which skips the shutdown hooks. */
    public boolean isHalt() {
        return halt;
    }
}
