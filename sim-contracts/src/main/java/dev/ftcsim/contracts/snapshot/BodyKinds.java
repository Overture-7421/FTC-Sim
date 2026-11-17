package dev.ftcsim.contracts.snapshot;

/** The kinds of body the simulator itself puts into a snapshot. The kinds of a field's own bodies start at 100. */
public final class BodyKinds {

    /** The robot's chassis. */
    public static final int CHASSIS = 1;

    /**
     * The turret of a robot that has one, a body the simulator puts into the snapshot for viewers: its origin is the
     * turret's axis and it is turned by the turret's yaw. It is not in the physics world and nothing collides with it.
     */
    public static final int TURRET = 2;

    private BodyKinds() {}
}
