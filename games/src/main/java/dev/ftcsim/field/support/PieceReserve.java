package dev.ftcsim.field.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Pieces a field holds out of play, by kind, handed out in the order they were added so that equal runs hand out
 * equal pieces.
 */
public final class PieceReserve {
    private final Map<Integer, Deque<Integer>> byKind = new TreeMap<>();

    /** Puts a piece, which must not be in the world, at the back of the queue for its kind. */
    public void add(int kind, int entityId) {
        byKind.computeIfAbsent(kind, key -> new ArrayDeque<>()).addLast(entityId);
    }

    /** Hands out the piece that has been waiting longest, or none if there is none of that kind. */
    public OptionalInt take(int kind) {
        Deque<Integer> queue = byKind.get(kind);
        return queue == null || queue.isEmpty() ? OptionalInt.empty() : OptionalInt.of(queue.removeFirst());
    }

    public int count(int kind) {
        Deque<Integer> queue = byKind.get(kind);
        return queue == null ? 0 : queue.size();
    }
}
