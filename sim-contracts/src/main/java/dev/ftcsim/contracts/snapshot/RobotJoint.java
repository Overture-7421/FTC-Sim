package dev.ftcsim.contracts.snapshot;

/**
 * The moving parts of the BIOBUZZ robot's picture whose motion the simulator tells the window: each has a body in the
 * snapshot whose kind and entity id are {@link BodyKinds#JOINT_BASE} plus its ordinal, and whose X is the joint's value in
 * radians (its angle, since the simulation began for a part that turns without end, or from level for the hood). The
 * turret's yaw is not here: it is the body of kind {@link BodyKinds#TURRET}.
 */
public enum RobotJoint {
    WHEEL_FRONT_LEFT("wheel.fl"),
    WHEEL_FRONT_RIGHT("wheel.fr"),
    WHEEL_REAR_LEFT("wheel.rl"),
    WHEEL_REAR_RIGHT("wheel.rr"),
    /** The intake roller; positive takes pieces in. */
    INTAKE("intake"),
    /** Every roller and sprocket of the indexer; positive feeds the shooter. */
    CONVEYOR("conveyor"),
    /** The flywheel and the feed rollers beside it. */
    FLYWHEEL("flywheel"),
    /** The colibri arms; positive raises the front. */
    COLIBRI_LEFT("colibri.left"),
    COLIBRI_RIGHT("colibri.right"),
    /** The angle of the hood above level. */
    HOOD("hood"),
    /** The stopper that holds pieces in the indexer; positive opens it. */
    STOPPER("stopper"),
    /** The gear of the first turret encoder in the profile, turning with the turret's yaw. */
    ENCODER_A("encoder.a"),
    /** The gear of the second turret encoder in the profile. */
    ENCODER_B("encoder.b");

    private final String modelName;

    RobotJoint(String modelName) {
        this.modelName = modelName;
    }

    /** The name the picture's groups use for the joint. */
    public String modelName() {
        return modelName;
    }

    /** The kind of body, and the entity id, that carries the joint's value. */
    public int kind() {
        return BodyKinds.JOINT_BASE + ordinal();
    }

    /** The joint a picture group names, or null for none. */
    public static RobotJoint named(String modelName) {
        for (RobotJoint joint : values()) {
            if (joint.modelName.equals(modelName)) {
                return joint;
            }
        }
        return null;
    }
}
