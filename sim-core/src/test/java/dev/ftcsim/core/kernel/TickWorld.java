package dev.ftcsim.core.kernel;

/** A world that does one tick per 1/240 s and counts them, so tests can see what the robot observes. */
final class TickWorld implements WorldStepper {
    static final int HZ = 240;

    private volatile int ticks;

    int ticks() {
        return ticks;
    }

    @Override
    public long nextStepNanos() {
        return Math.floorDiv((ticks + 1L) * 1_000_000_000L, HZ);
    }

    @Override
    public void advanceTo(long nanos) {
        while (nextStepNanos() <= nanos) {
            ticks++;
        }
    }

    /** The tick count a robot reading the world at the given time must see. */
    static int ticksAt(long nanos) {
        return (int) Math.floorDiv(nanos * HZ, 1_000_000_000L);
    }
}
