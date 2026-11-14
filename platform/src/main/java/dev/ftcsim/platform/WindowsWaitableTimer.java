package dev.ftcsim.platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Win32 high-resolution waitable timer, reached through the foreign function API, so the JVM needs
 * {@code --enable-native-access} to run it without a warning. One instance must not be waited on from two
 * threads at once.
 */
@SuppressWarnings("restricted")
public final class WindowsWaitableTimer implements AutoCloseable {
    private static final int CREATE_WAITABLE_TIMER_HIGH_RESOLUTION = 0x2;
    private static final int TIMER_ALL_ACCESS = 0x1F0003;
    private static final int INFINITE = -1;

    private final MethodHandle setTimer;
    private final MethodHandle waitFor;
    private final MethodHandle closeHandle;
    private final MemorySegment timer;
    private final Arena memory;
    private final MemorySegment due;

    private WindowsWaitableTimer(
            MethodHandle setTimer,
            MethodHandle waitFor,
            MethodHandle closeHandle,
            MemorySegment timer,
            Arena memory) {
        this.setTimer = setTimer;
        this.waitFor = waitFor;
        this.closeHandle = closeHandle;
        this.timer = timer;
        this.memory = memory;
        this.due = memory.allocate(Long.BYTES, Long.BYTES);
    }

    public static WindowsWaitableTimer openHighResolution() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MethodHandle create = downcall(linker, kernel32, "CreateWaitableTimerExW", FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle setTimer = downcall(linker, kernel32, "SetWaitableTimer", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle waitFor = downcall(linker, kernel32, "WaitForSingleObject", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle closeHandle = downcall(linker, kernel32, "CloseHandle", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        try {
            MemorySegment timer = (MemorySegment) create.invoke(
                    MemorySegment.NULL, MemorySegment.NULL, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
            if (timer.address() == 0L) {
                throw new IllegalStateException("CreateWaitableTimerExW failed; high-resolution timers need Windows 10 1803+");
            }
            return new WindowsWaitableTimer(setTimer, waitFor, closeHandle, timer, Arena.ofShared());
        } catch (Throwable e) {
            throw unchecked(e);
        }
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup library, String function, FunctionDescriptor descriptor) {
        return linker.downcallHandle(library.find(function).orElseThrow(), descriptor);
    }

    /** Waits for the requested time with the timer's own resolution rather than the JVM's millisecond waits. */
    public void parkNanos(long nanos) {
        try {
            due.set(ValueLayout.JAVA_LONG, 0, -Math.max(1L, nanos / 100L));
            int armed = (int) setTimer.invoke(timer, due, 0, MemorySegment.NULL, MemorySegment.NULL, 0);
            if (armed == 0) {
                throw new IllegalStateException("SetWaitableTimer failed");
            }
            int ignored = (int) waitFor.invoke(timer, INFINITE);
            assert ignored == 0 : "unexpected wait result " + ignored;
        } catch (Throwable e) {
            throw unchecked(e);
        }
    }

    @Override
    public void close() {
        try {
            int ignored = (int) closeHandle.invoke(timer);
            assert ignored != 0 : "CloseHandle failed";
        } catch (Throwable e) {
            throw unchecked(e);
        } finally {
            memory.close();
        }
    }

    /** Method handles throw {@link Throwable}; unchecked exceptions and errors pass through unchanged. */
    private static IllegalStateException unchecked(Throwable e) {
        if (e instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (e instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(e);
    }
}
