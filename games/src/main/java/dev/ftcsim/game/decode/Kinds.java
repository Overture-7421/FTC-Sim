package dev.ftcsim.game.decode;

/** The numbers under which the field's bodies are known to snapshots, which are the {@code kind} of each body. */
final class Kinds {

    static final int PURPLE = 101;
    static final int GREEN = 102;
    static final int BLUE_GATE = 103;
    static final int RED_GATE = 104;
    static final int OBELISK = 105;

    private Kinds() {}

    static boolean isArtifact(int kind) {
        return kind == PURPLE || kind == GREEN;
    }
}
