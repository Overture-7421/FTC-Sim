package dev.ftcsim.app;

import java.io.IOException;

/** Something the application reads when it needs it, not when it starts, so that what is wrong can be said where it matters. */
@FunctionalInterface
interface Source<T> {
    T get() throws IOException;
}
