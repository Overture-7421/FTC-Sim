package dev.ftcsim.hardware.lynx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * The hub's analog measurements in the units of the {@code GetADC} command's engineering mode: millivolts,
 * milliamps and tenths of a degree Celsius. Motor currents and the battery voltage come from the simulation; the
 * other currents and the temperature are simple estimates, named below, since nothing in the simulation measures them.
 */
final class AdcBank {

    static final int USER_INPUT_COUNT = 4;

    private static final int INPUT_MAX_COUNT = 4095;

    private static final int HUB_IDLE_MILLIAMPS = 200;
    private static final int SERVO_HOLD_MILLIAMPS = 10;
    private static final int I2C_DEVICE_MILLIAMPS = 5;
    private static final int GPIO_OUTPUT_HIGH_MILLIAMPS = 2;
    private static final int FIVE_VOLT_RAIL_MILLIVOLTS = 5000;
    private static final int AMBIENT_DECI_CELSIUS = 250;
    private static final int DECI_CELSIUS_PER_AMP = 2;

    private final DoubleSupplier battery;
    private final MotorBank motors;
    private final PulseBank servos;
    private final DigitalBank digital;
    private final IntSupplier attachedI2cDevices;
    private final Diagnostics diagnostics;
    private final List<Port<AnalogInput>> ports = new ArrayList<>();

    AdcBank(
            int hubAddress,
            DoubleSupplier battery,
            MotorBank motors,
            PulseBank servos,
            DigitalBank digital,
            IntSupplier attachedI2cDevices,
            Diagnostics diagnostics) {
        this.battery = battery;
        this.motors = motors;
        this.servos = servos;
        this.digital = digital;
        this.attachedI2cDevices = attachedI2cDevices;
        this.diagnostics = diagnostics;
        for (int channel = 0; channel < USER_INPUT_COUNT; channel++) {
            ports.add(new Port<>("hub " + hubAddress + " analog port " + channel));
        }
    }

    Port<AnalogInput> port(int channel) {
        return ports.get(channel);
    }

    /** The user input in millivolts without noting whether anything is connected, as the bulk read does. */
    int userMillivolts(int channel, long nowNanos) {
        return (int) Math.round(volts(channel, nowNanos) * 1000.0);
    }

    /** The measurement in millivolts, milliamps or tenths of a degree, saturated to what the response can carry. */
    int engineering(AdcChannel channel, long nowNanos) {
        int value =
                switch (channel) {
                    case USER0, USER1, USER2, USER3 -> {
                        noteIfUnbound(channel.ordinal());
                        yield userMillivolts(channel.ordinal(), nowNanos);
                    }
                    case GPIO_CURRENT -> gpioCurrentMilliamps();
                    case I2C_BUS_CURRENT -> i2cCurrentMilliamps();
                    case SERVO_CURRENT -> servoCurrentMilliamps();
                    case BATTERY_CURRENT -> batteryCurrentMilliamps();
                    case MOTOR0_CURRENT, MOTOR1_CURRENT, MOTOR2_CURRENT, MOTOR3_CURRENT ->
                        motors.channel(channel.ordinal() - AdcChannel.MOTOR0_CURRENT.ordinal()).currentMilliamps();
                    case FIVE_VOLT_MONITOR -> FIVE_VOLT_RAIL_MILLIVOLTS;
                    case BATTERY_MONITOR -> (int) Math.round(battery.getAsDouble() * 1000.0);
                    case CONTROLLER_TEMPERATURE -> temperatureDeciCelsius();
                };
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    /** The user input as the raw 12-bit conversion of its 0 to 3.3 V range. */
    int userRawCounts(AdcChannel channel, long nowNanos) {
        noteIfUnbound(channel.ordinal());
        return (int) Math.round(volts(channel.ordinal(), nowNanos) / AnalogInput.FULL_SCALE_VOLTS * INPUT_MAX_COUNT);
    }

    private double volts(int channel, long nowNanos) {
        AnalogInput source = ports.get(channel).device();
        double volts = source == null ? 0.0 : source.volts(nowNanos);
        return Math.max(0.0, Math.min(AnalogInput.FULL_SCALE_VOLTS, volts));
    }

    private void noteIfUnbound(int channel) {
        if (!ports.get(channel).isConnected()) {
            diagnostics.analogReadWhileUnbound(channel);
        }
    }

    private int servoCurrentMilliamps() {
        return servos.enabledCount() * SERVO_HOLD_MILLIAMPS;
    }

    private int i2cCurrentMilliamps() {
        return attachedI2cDevices.getAsInt() * I2C_DEVICE_MILLIAMPS;
    }

    private int gpioCurrentMilliamps() {
        return digital.drivenHighCount() * GPIO_OUTPUT_HIGH_MILLIAMPS;
    }

    private int batteryCurrentMilliamps() {
        return HUB_IDLE_MILLIAMPS
                + motors.totalCurrentMilliamps()
                + servoCurrentMilliamps()
                + i2cCurrentMilliamps()
                + gpioCurrentMilliamps();
    }

    private int temperatureDeciCelsius() {
        return AMBIENT_DECI_CELSIUS + DECI_CELSIUS_PER_AMP * (int) Math.round(motors.totalCurrentMilliamps() / 1000.0);
    }
}
