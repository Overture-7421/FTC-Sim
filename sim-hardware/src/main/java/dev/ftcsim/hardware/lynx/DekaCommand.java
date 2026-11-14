package dev.ftcsim.hardware.lynx;

/**
 * The 54 commands of the "DEKA" interface, in the order of the table the SDK builds in
 * {@code LynxDekaInterfaceCommand.createDekaInterface()}. A module reports the number of the first one when it is
 * queried for the interface, and a command's number is that base plus its index.
 */
public enum DekaCommand {
    GET_BULK_INPUT_DATA,
    SET_SINGLE_DIO_OUTPUT,
    SET_ALL_DIO_OUTPUTS,
    SET_DIO_DIRECTION,
    GET_DIO_DIRECTION,
    GET_SINGLE_DIO_INPUT,
    GET_ALL_DIO_INPUTS,
    GET_ADC,
    SET_MOTOR_CHANNEL_MODE,
    GET_MOTOR_CHANNEL_MODE,
    SET_MOTOR_CHANNEL_ENABLE,
    GET_MOTOR_CHANNEL_ENABLE,
    SET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL,
    GET_MOTOR_CHANNEL_CURRENT_ALERT_LEVEL,
    RESET_MOTOR_ENCODER,
    SET_MOTOR_CONSTANT_POWER,
    GET_MOTOR_CONSTANT_POWER,
    SET_MOTOR_TARGET_VELOCITY,
    GET_MOTOR_TARGET_VELOCITY,
    SET_MOTOR_TARGET_POSITION,
    GET_MOTOR_TARGET_POSITION,
    IS_MOTOR_AT_TARGET,
    GET_MOTOR_ENCODER_POSITION,
    SET_MOTOR_PID_COEFFICIENTS,
    GET_MOTOR_PID_COEFFICIENTS,
    SET_PWM_CONFIGURATION,
    GET_PWM_CONFIGURATION,
    SET_PWM_PULSE_WIDTH,
    GET_PWM_PULSE_WIDTH,
    SET_PWM_ENABLE,
    GET_PWM_ENABLE,
    SET_SERVO_CONFIGURATION,
    GET_SERVO_CONFIGURATION,
    SET_SERVO_PULSE_WIDTH,
    GET_SERVO_PULSE_WIDTH,
    SET_SERVO_ENABLE,
    GET_SERVO_ENABLE,
    I2C_WRITE_SINGLE_BYTE,
    I2C_WRITE_MULTIPLE_BYTES,
    I2C_READ_SINGLE_BYTE,
    I2C_READ_MULTIPLE_BYTES,
    I2C_READ_STATUS_QUERY,
    I2C_WRITE_STATUS_QUERY,
    I2C_CONFIGURE_CHANNEL,
    PHONE_CHARGE_CONTROL,
    PHONE_CHARGE_QUERY,
    INJECT_DATA_LOG_HINT,
    I2C_CONFIGURE_QUERY,
    READ_VERSION_STRING,
    FTDI_RESET_CONTROL,
    FTDI_RESET_QUERY,
    SET_MOTOR_PIDF_COEFFICIENTS,
    I2C_WRITE_READ_MULTIPLE_BYTES,
    GET_MOTOR_PIDF_COEFFICIENTS;

    /** The interface name a host passes to {@code QueryInterface}. */
    public static final String INTERFACE_NAME = "DEKA";

    /** The number of the first DEKA command, as real hubs report it. */
    public static final int BASE = 0x1000;

    /** The packet id of this command on a module that reports {@link #BASE}. */
    public int number() {
        return BASE + ordinal();
    }

    /** Whether the command is one to an I2C bus, whose transactions the host waits on. */
    boolean isI2c() {
        return name().startsWith("I2C_");
    }
}
