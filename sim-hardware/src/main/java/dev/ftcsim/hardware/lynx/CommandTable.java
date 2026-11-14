package dev.ftcsim.hardware.lynx;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** Maps packet ids to the commands that execute them, and records what each command does to the module. */
final class CommandTable {

    /**
     * What a command does to the module. A query reads what the host can observe and starts nothing. Everything else
     * changes what the host can observe, so it matters who sends it and when; a command that hands over a result that
     * is waiting for the host, so that nobody else can have it, is one of those.
     */
    enum Effect {
        QUERY,
        MUTATING
    }

    /** Tells a request's effect from its payload, for the commands that sometimes only read and sometimes change. */
    @FunctionalInterface
    interface Classifier {
        Effect of(byte[] payload);
    }

    @FunctionalInterface
    interface Handler {
        Reply handle(Params params);
    }

    /** A registered command: its name, what it does to the module and the handler that executes it. */
    record Entry(String name, Classifier effect, Handler handler) {}

    private final Map<Integer, Entry> entries = new TreeMap<>();

    void register(StandardCommand command, Effect effect, Handler handler) {
        put(command.number(), command.name(), payload -> effect, handler);
    }

    void register(StandardCommand command, Classifier effect, Handler handler) {
        put(command.number(), command.name(), effect, handler);
    }

    void register(DekaCommand command, Effect effect, Handler handler) {
        put(command.number(), command.name(), payload -> effect, handler);
    }

    /** The command with the packet id, or null when the module has no such command. */
    Entry find(int packetId) {
        return entries.get(packetId);
    }

    /** Every registered command, in order of packet id. */
    Collection<Entry> entries() {
        return entries.values();
    }

    private void put(int packetId, String name, Classifier effect, Handler handler) {
        if (entries.put(packetId, new Entry(name, effect, handler)) != null) {
            throw new IllegalStateException("command registered twice: 0x" + Integer.toHexString(packetId));
        }
    }
}
