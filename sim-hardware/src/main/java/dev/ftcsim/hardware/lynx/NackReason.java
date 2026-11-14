package dev.ftcsim.hardware.lynx;

/** The reason codes of a NACK, from the SDK's {@code LynxNack.StandardReasonCode}. */
public final class NackReason {

    /** The command is known and delivered but its implementation is not complete. */
    public static final int COMMAND_IMPL_PENDING = 253;
    /** No command has this packet id. */
    public static final int PACKET_TYPE_ID_UNKNOWN = 255;
    public static final int GPIO_NO_OUTPUT = 18;
    public static final int GPIO_NO_INPUT = 28;
    public static final int SERVO_NOT_CONFIG_BEFORE_ENABLED = 30;
    /** A transaction is running; the command is rejected. */
    public static final int I2C_MASTER_BUSY = 40;
    /** The transaction a status query asks about has not finished; poll again. */
    public static final int I2C_OPERATION_IN_PROGRESS = 41;
    public static final int I2C_NO_RESULTS_PENDING = 42;
    /** A read query after a write, or a write query after a read. */
    public static final int I2C_QUERY_MISMATCH = 43;
    public static final int MOTOR_NOT_CONFIG_BEFORE_ENABLED = 50;
    public static final int COMMAND_INVALID_FOR_MOTOR_MODE = 51;

    private static final int LAST_PARAM = 9;
    private static final int GPIO_OUT_FIRST = 10;
    private static final int GPIO_IN_FIRST = 20;

    private NackReason() {}

    /** Parameter {@code index} of the command (counting from 0) is invalid or missing. */
    public static int param(int index) {
        return Math.min(index, LAST_PARAM);
    }

    /** The output pin cannot be set because it is not configured as an output. */
    public static int gpioOut(int pin) {
        return GPIO_OUT_FIRST + pin;
    }

    /** The input pin cannot be read because it is not configured as an input. */
    public static int gpioIn(int pin) {
        return GPIO_IN_FIRST + pin;
    }
}
