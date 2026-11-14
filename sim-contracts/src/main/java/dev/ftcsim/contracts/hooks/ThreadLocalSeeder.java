package dev.ftcsim.contracts.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives {@code ThreadLocalRandom} numbers that follow from the host's seed. The generator keeps its state in a field of
 * the {@code Thread} itself, whose initial value the JDK takes from the clock and the operating system, and each number
 * is that field plus a step that includes the thread's id, which is a count of the threads the JVM created before, so
 * that the same seed gives other numbers in another thread. The only way to set the field is {@code sun.misc.Unsafe},
 * which this reaches by reflection and which JDK 24 and later warn about once, or a JVM that opens {@code java.lang}.
 *
 * <p>The field is set each time the program asks for the generator, so that the number it draws first, which is how the
 * idiom {@code ThreadLocalRandom.current().nextInt()} draws one, follows from the host's seed whatever the thread's id.
 * A program that keeps the generator and draws from it again gets numbers that differ with the thread's id, as the
 * documentation of the class warns it should not do. A JVM that does not let the field be set leaves all of them
 * unreproducible, and the host is told.
 */
final class ThreadLocalSeeder {
    private static final MethodHandle PUT_LONG;
    private static final long SEED_OFFSET;
    private static final String UNAVAILABLE;

    static {
        MethodHandle putLong = null;
        long seedOffset = 0;
        String unavailable = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field instance = unsafeClass.getDeclaredField("theUnsafe");
            instance.setAccessible(true);
            Object unsafe = instance.get(null);
            Method offsetOf = unsafeClass.getMethod("objectFieldOffset", Field.class);
            seedOffset = (long) offsetOf.invoke(unsafe, Thread.class.getDeclaredField("threadLocalRandomSeed"));
            putLong = MethodHandles.lookup()
                    .findVirtual(unsafeClass, "putLong", MethodType.methodType(void.class, Object.class, long.class, long.class))
                    .bindTo(unsafe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unavailable = failure.toString();
        }
        PUT_LONG = putLong;
        SEED_OFFSET = seedOffset;
        UNAVAILABLE = unavailable;
    }

    private ThreadLocalSeeder() {}

    /** The calling thread's {@code ThreadLocalRandom}, ready to draw a number that follows from the host's seed. */
    static ThreadLocalRandom current() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (PUT_LONG == null) {
            Hooks.nondeterministic("ThreadLocalRandom was used, and this JVM does not let its seed be set (" + UNAVAILABLE
                    + "), so its numbers are not reproducible");
            return random;
        }
        Thread thread = Thread.currentThread();
        try {
            // The JDK adds twice the thread's id to the field before it mixes it, so that is taken off here.
            PUT_LONG.invoke(thread, SEED_OFFSET, Hooks.randomSeed() - (thread.threadId() << 1));
        } catch (Throwable failure) {
            Hooks.nondeterministic("ThreadLocalRandom was used, and its seed could not be set (" + failure
                    + "), so its numbers are not reproducible");
        }
        return random;
    }
}
