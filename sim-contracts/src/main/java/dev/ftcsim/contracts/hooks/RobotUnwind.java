package dev.ftcsim.contracts.hooks;

/**
 * Thrown in a robot thread by the host to end it when it will not end by itself, for instance because it ignores a
 * Stop. It is an {@link Error} so that the code that catches exceptions, which is nearly all of it, lets it through:
 * every {@code finally} block runs and the thread ends. Code that catches {@code Throwable} and carries on is asked
 * again.
 */
public class RobotUnwind extends Error {
    private static final long serialVersionUID = 1L;

    public RobotUnwind(String message) {
        super(message);
    }
}
