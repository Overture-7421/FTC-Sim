package dev.ftcsim.hardware.lynx;

/** The channels of the hub's ADC, numbered as the {@code GetADC} command numbers them. */
enum AdcChannel {
    USER0,
    USER1,
    USER2,
    USER3,
    GPIO_CURRENT,
    I2C_BUS_CURRENT,
    SERVO_CURRENT,
    BATTERY_CURRENT,
    MOTOR0_CURRENT,
    MOTOR1_CURRENT,
    MOTOR2_CURRENT,
    MOTOR3_CURRENT,
    FIVE_VOLT_MONITOR,
    BATTERY_MONITOR,
    CONTROLLER_TEMPERATURE;

    private static final AdcChannel[] BY_WIRE_VALUE = values();

    static AdcChannel fromWire(int value) {
        return BY_WIRE_VALUE[value];
    }

    boolean isUserInput() {
        return ordinal() <= USER3.ordinal();
    }
}
