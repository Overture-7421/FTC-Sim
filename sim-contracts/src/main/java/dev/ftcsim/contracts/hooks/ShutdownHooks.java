package dev.ftcsim.contracts.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The shutdown hooks a robot program registered, kept with the checks the JDK makes when one is registered. */
final class ShutdownHooks {
    private final List<Thread> hooks = new ArrayList<>();

    synchronized void add(Thread hook) {
        Objects.requireNonNull(hook, "hook");
        if (hook.isAlive()) {
            throw new IllegalArgumentException("Hook already running");
        }
        if (hooks.contains(hook)) {
            throw new IllegalArgumentException("Hook previously registered");
        }
        hooks.add(hook);
    }

    synchronized boolean remove(Thread hook) {
        Objects.requireNonNull(hook, "hook");
        return hooks.remove(hook);
    }

    synchronized List<Thread> registered() {
        return List.copyOf(hooks);
    }

    synchronized void clear() {
        hooks.clear();
    }
}
