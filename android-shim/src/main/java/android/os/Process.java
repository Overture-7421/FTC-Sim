package android.os;

/** Process and thread identity. */
public final class Process {
    private Process() {}

    public static int myPid() {
        return (int) ProcessHandle.current().pid();
    }

    public static int myTid() {
        return (int) Thread.currentThread().threadId();
    }
}
