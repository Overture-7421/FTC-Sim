package dev.ftcsim.hardware.lynx;

/** How a motor channel turns its setpoint into power; the wire value is the ordinal. */
enum RunMode {
    /** Constant power: RUN_WITHOUT_ENCODER. */
    OPEN_LOOP,
    /** Regulated velocity: RUN_USING_ENCODER. */
    VELOCITY,
    /** Regulated position with a velocity limit: RUN_TO_POSITION. */
    POSITION;

    private static final RunMode[] BY_WIRE_VALUE = values();

    static RunMode fromWire(int value) {
        return BY_WIRE_VALUE[value];
    }

    int wire() {
        return ordinal();
    }
}
