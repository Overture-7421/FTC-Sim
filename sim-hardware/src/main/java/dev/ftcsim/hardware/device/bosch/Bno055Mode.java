package dev.ftcsim.hardware.device.bosch;

/**
 * The operation modes of the BNO055 (datasheet table 3-5): which of its sensors each one runs, and whether the
 * sensor fusion algorithm, which produces orientation, gravity and linear acceleration, runs on top of them.
 */
enum Bno055Mode {
    CONFIG(0x00, false, false, false, false),
    ACCONLY(0x01, true, false, false, false),
    MAGONLY(0x02, false, true, false, false),
    GYRONLY(0x03, false, false, true, false),
    ACCMAG(0x04, true, true, false, false),
    ACCGYRO(0x05, true, false, true, false),
    MAGGYRO(0x06, false, true, true, false),
    AMG(0x07, true, true, true, false),
    IMU(0x08, true, false, true, true),
    COMPASS(0x09, true, true, false, true),
    M4G(0x0A, true, true, false, true),
    NDOF_FMC_OFF(0x0B, true, true, true, true),
    NDOF(0x0C, true, true, true, true);

    final int bits;
    final boolean accelerometer;
    final boolean magnetometer;
    final boolean gyroscope;
    final boolean fusion;

    Bno055Mode(int bits, boolean accelerometer, boolean magnetometer, boolean gyroscope, boolean fusion) {
        this.bits = bits;
        this.accelerometer = accelerometer;
        this.magnetometer = magnetometer;
        this.gyroscope = gyroscope;
        this.fusion = fusion;
    }

    /** The mode a value of the OPR_MODE register selects, or null for a value that is not a mode. */
    static Bno055Mode fromBits(int value) {
        for (Bno055Mode mode : values()) {
            if (mode.bits == value) {
                return mode;
            }
        }
        return null;
    }
}
