package dev.ftcsim.ui.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Keys currently held down for the robot controls. Presses are accepted only while capture is enabled, releases
 * are always honoured so a key can never stay stuck, and capture ending releases everything at once, which is what
 * happens when the viewport loses focus or a modal opens. Called from UI threads, so it is synchronized; the change
 * listener is called after the lock is released.
 */
public final class HeldKeys {
    private final Set<Integer> held = new TreeSet<>();
    private final List<String> neutralizations = new ArrayList<>();
    private boolean captureEnabled;
    private int ignoredPresses;
    private volatile Runnable changed = () -> {};

    /** Called whenever the set of held keys changes, from the thread that changed it. */
    public void onChange(Runnable listener) {
        changed = listener;
    }

    /** Turns capture on or off; turning it off releases held keys so none can leak across the change. */
    public void setCaptureEnabled(boolean enabled, String reason) {
        boolean released;
        synchronized (this) {
            if (captureEnabled == enabled) {
                return;
            }
            captureEnabled = enabled;
            released = !enabled && releaseAll(reason);
        }
        if (released) {
            changed.run();
        }
    }

    public synchronized boolean captureEnabled() {
        return captureEnabled;
    }

    public void press(int keyCode) {
        boolean added;
        synchronized (this) {
            if (!captureEnabled) {
                ignoredPresses++;
                return;
            }
            added = held.add(keyCode);
        }
        if (added) {
            changed.run();
        }
    }

    public void release(int keyCode) {
        boolean removed;
        synchronized (this) {
            removed = held.remove(keyCode);
        }
        if (removed) {
            changed.run();
        }
    }

    /** Releases every held key; records the reason only when something was actually held. */
    public void neutralize(String reason) {
        boolean released;
        synchronized (this) {
            released = releaseAll(reason);
        }
        if (released) {
            changed.run();
        }
    }

    private boolean releaseAll(String reason) {
        if (held.isEmpty()) {
            return false;
        }
        neutralizations.add(reason + " released " + held);
        held.clear();
        return true;
    }

    public synchronized Set<Integer> held() {
        return new TreeSet<>(held);
    }

    public synchronized int ignoredPresses() {
        return ignoredPresses;
    }

    public synchronized List<String> neutralizations() {
        return List.copyOf(neutralizations);
    }
}
