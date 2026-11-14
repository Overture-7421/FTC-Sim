package dev.ftcsim.link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The last lines a process wrote, both streams together, kept in a fixed amount of memory. */
final class LineRing {

    private record Line(boolean error, String text) {}

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final int capacity;

    LineRing(int capacity) {
        this.capacity = capacity;
    }

    synchronized void add(boolean error, String text) {
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast(new Line(error, text));
    }

    /** The last {@code max} lines of the error output, oldest first. */
    synchronized List<String> errorLines(int max) {
        List<String> found = new ArrayList<>();
        for (Iterator<Line> it = lines.descendingIterator(); it.hasNext() && found.size() < max; ) {
            Line line = it.next();
            if (line.error()) {
                found.add(0, line.text());
            }
        }
        return found;
    }
}
