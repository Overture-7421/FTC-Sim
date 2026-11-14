package dev.ftcsim.link;

import dev.ftcsim.link.wire.Command;
import java.util.ArrayDeque;

/**
 * The commands waiting to be written to the worker. Commands go out in the order they were given. A gamepad is a
 * state and not an event, so of each gamepad only the latest state waits, in the place of the call that set it:
 * when the writer is behind, the frames in between are never sent.
 */
final class Outbox {

    private record Entry(long sequence, Command command) {}

    private final Object lock = new Object();
    private final ArrayDeque<Entry> commands = new ArrayDeque<>();
    private final Entry[] gamepads = new Entry[2];
    private long sequence;
    private boolean finished;

    void add(Command command) {
        synchronized (lock) {
            if (finished) {
                return;
            }
            commands.addLast(new Entry(++sequence, command));
            lock.notifyAll();
        }
    }

    void setGamepad(Command.SetGamepad command) {
        synchronized (lock) {
            if (finished) {
                return;
            }
            gamepads[command.gamepad() - 1] = new Entry(++sequence, command);
            lock.notifyAll();
        }
    }

    /** Drops everything that waits and lets {@code last} go out, after which {@link #take} returns null. */
    void finishWith(Command last) {
        synchronized (lock) {
            clear();
            if (!finished) {
                commands.addLast(new Entry(++sequence, last));
                finished = true;
            }
            lock.notifyAll();
        }
    }

    /** Drops everything that waits; {@link #take} returns null from now on. */
    void abandon() {
        synchronized (lock) {
            clear();
            finished = true;
            lock.notifyAll();
        }
    }

    /** The next command to write, waiting for one; null when the outbox is finished and empty. */
    Command take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                Entry next = earliest();
                if (next != null) {
                    if (next == gamepads[0]) {
                        gamepads[0] = null;
                    } else if (next == gamepads[1]) {
                        gamepads[1] = null;
                    } else {
                        commands.removeFirst();
                    }
                    return next.command();
                }
                if (finished) {
                    return null;
                }
                lock.wait();
            }
        }
    }

    private Entry earliest() {
        Entry best = commands.peekFirst();
        for (Entry gamepad : gamepads) {
            if (gamepad != null && (best == null || gamepad.sequence() < best.sequence())) {
                best = gamepad;
            }
        }
        return best;
    }

    private void clear() {
        commands.clear();
        gamepads[0] = null;
        gamepads[1] = null;
    }
}
