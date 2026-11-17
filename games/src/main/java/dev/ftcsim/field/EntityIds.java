package dev.ftcsim.field;

/** Where each owner of entity ids starts counting, so that nothing in the world ever shares an id. */
public final class EntityIds {

    /** The robot's chassis. */
    public static final int CHASSIS = 1;

    /** The robot's turret, in the snapshot only. */
    public static final int TURRET = 2;

    /** The first id a field may use for its pieces and moving structures. */
    public static final int FIELD_BASE = 1000;

    private EntityIds() {}
}
