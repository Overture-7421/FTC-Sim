package dev.ftcsim.game.biobuzz;

import dev.ftcsim.field.EntityIds;

/** The numbers the BIOBUZZ field gives to the kinds of bodies it has and to every body of its own. */
public final class BiobuzzIds {

    /** A yellow POLLEN ball. */
    public static final int KIND_POLLEN = 101;
    public static final int KIND_NECTAR_RED = 102;
    public static final int KIND_NECTAR_BLUE = 103;
    /** The red HIVE: the two red CELLS and the arm between them, which tip about one pivot. */
    public static final int KIND_HIVE_RED = 104;
    public static final int KIND_HIVE_BLUE = 105;

    public static final int POLLEN_COUNT = 40;
    public static final int NECTAR_PER_ALLIANCE = 8;

    /** Pollen take {@code POLLEN_FIRST} to {@code POLLEN_FIRST + 39}. */
    public static final int POLLEN_FIRST = EntityIds.FIELD_BASE;
    public static final int NECTAR_RED_FIRST = POLLEN_FIRST + POLLEN_COUNT;
    public static final int NECTAR_BLUE_FIRST = NECTAR_RED_FIRST + NECTAR_PER_ALLIANCE;
    public static final int HIVE_RED = NECTAR_BLUE_FIRST + NECTAR_PER_ALLIANCE;
    public static final int HIVE_BLUE = HIVE_RED + 1;

    private BiobuzzIds() {}
}
