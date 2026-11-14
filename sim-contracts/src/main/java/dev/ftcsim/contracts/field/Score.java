package dev.ftcsim.contracts.field;

import java.util.List;

/** The points of one alliance as the game counts them, with the lines that make them up. */
public record Score(List<Line> lines) {

    /** What was counted: how many of it and the points they are worth together. */
    public record Line(String label, int count, int points) {}

    public Score {
        lines = List.copyOf(lines);
    }

    public int total() {
        return lines.stream().mapToInt(Line::points).sum();
    }
}
