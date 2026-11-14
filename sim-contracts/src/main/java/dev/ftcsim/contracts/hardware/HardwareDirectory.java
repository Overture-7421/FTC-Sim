package dev.ftcsim.contracts.hardware;

import java.util.List;

/**
 * Where the robot's devices are connected. The simulator's robot profile implements this; the SDK adapter asks
 * it the first time robot code looks a device up by name, so a robot program never has to be told what is
 * plugged in where. A name the profile does not mention still gets a working port, the next free one, and the
 * answer says so, so the simulator can show it as unbound instead of pretending it is wired to something real.
 */
public interface HardwareDirectory {

    /** The I2C bus of a hub that its own IMU, the BNO055 inside the hub, is on. */
    int EMBEDDED_IMU_BUS = 0;

    /** One REV hub on the bus. */
    record Hub(int address, String name, boolean parent, boolean embedded) {
        public Hub {
            if (address < 1 || address > 254) {
                throw new IllegalArgumentException("a hub address is 1..254: " + address);
            }
        }
    }

    /** Where a device was found, and whether the profile put it there or the simulator picked a free port. */
    sealed interface Wiring permits MotorWiring, ServoWiring, I2cWiring, DigitalWiring, AnalogWiring {
        int hubAddress();

        boolean mapped();
    }

    /**
     * @param ticksPerRev encoder counts per revolution of the output shaft
     * @param maxRpm      free speed of the output shaft at nominal voltage
     */
    record MotorWiring(int hubAddress, int port, double ticksPerRev, double maxRpm, boolean mapped) implements Wiring {}

    record ServoWiring(int hubAddress, int port, boolean mapped) implements Wiring {}

    record I2cWiring(int hubAddress, int bus, boolean mapped) implements Wiring {}

    record DigitalWiring(int hubAddress, int channel, boolean mapped) implements Wiring {}

    record AnalogWiring(int hubAddress, int channel, boolean mapped) implements Wiring {}

    /** The hubs the robot controller has, parent first. Always at least the embedded Control Hub. */
    List<Hub> hubs();

    MotorWiring motor(String name);

    ServoWiring servo(String name);

    I2cWiring i2c(String name);

    /**
     * Where the IMU is for robot code that asks for an IMU, the {@code IMU} interface or {@code BNO055IMU}, under
     * {@code name}. A name the profile places is wired where the profile says. Any other name is the IMU inside the
     * embedded hub, on its bus 0, whatever the name is: robot code calls it "imu", "IMU", "gyro" or anything else, and
     * the profile is not asked to guess. A hub has one such chip, so one name can be it, as a robot configuration has
     * one entry for it; asking for it under another name is refused.
     *
     * @throws IllegalStateException if the robot has no embedded hub, or the IMU of the embedded hub already is
     *                               another name
     */
    I2cWiring imu(String name);

    DigitalWiring digital(String name);

    AnalogWiring analog(String name);
}
