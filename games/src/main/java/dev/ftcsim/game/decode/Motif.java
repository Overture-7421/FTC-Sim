package dev.ftcsim.game.decode;

/** The three patterns the OBELISK can show, each named after the tag that faces the field. */
enum Motif {
    GPP(21, "GPP"),
    PGP(22, "PGP"),
    PPG(23, "PPG");

    private final int tagId;
    private final String colors;

    Motif(int tagId, String colors) {
        this.tagId = tagId;
        this.colors = colors;
    }

    /** The AprilTag id that is on the OBELISK's face for this pattern. */
    int tagId() {
        return tagId;
    }

    /** The kind of artifact that scores at ramp index 1 to 9, counted from the gate; the motif repeats three times. */
    int kindAtIndex(int index) {
        return colors.charAt((index - 1) % colors.length()) == 'G' ? Kinds.GREEN : Kinds.PURPLE;
    }
}
