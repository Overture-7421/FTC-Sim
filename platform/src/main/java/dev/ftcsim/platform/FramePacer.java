package dev.ftcsim.platform;

/** Holds a render loop to a cadence by blocking the calling thread. */
public interface FramePacer {
    /** A pacer for loops that are already blocked by the presentation call itself, such as a vsynced swap. */
    FramePacer NONE = () -> 0L;

    /** Blocks until the next frame boundary and returns how many nanoseconds past it the call woke. */
    long awaitNextFrame();
}
