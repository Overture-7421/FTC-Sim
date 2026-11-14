package dev.ftcsim.runtime.fixture;

import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/** Reads the clock through a method reference, which compiles to an invokedynamic handle rather than a call. */
public final class MethodReferenceProbe implements Callable<Long> {
    @Override
    public Long call() {
        LongSupplier clock = System::nanoTime;
        return clock.getAsLong();
    }
}
