package dev.ftcsim.hardware.wiring;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each named device is plugged in, as the robot profile says, with the next free port for any name the
 * profile does not mention. An assignment, once made, never changes, so a name always finds the same port, and
 * every name that had to be assigned is remembered so the simulator can show it as unbound. The IMU of the embedded
 * hub is the one device that exists without a profile line, so robot code that asks for an IMU under a name the
 * profile does not place gets it (once, under the first name that asks) instead of a free port; it is not unbound.
 */
public final class WiringTable implements HardwareDirectory {
    private static final int MOTOR_PORTS = 4;
    private static final int SERVO_PORTS = 6;
    private static final int I2C_BUSES = 4;
    private static final int DIGITAL_CHANNELS = 8;
    private static final int ANALOG_CHANNELS = 4;
    /** Bus 0 of a Control Hub is taken by its own IMU, so user devices start at bus 1. */
    private static final int FIRST_USER_I2C_BUS = 1;

    private record Slot(int hub, int index) {}

    private final List<Hub> hubs;
    private final MotorSpec defaultMotor;
    private final Map<String, MotorWiring> motors = new LinkedHashMap<>();
    private final Map<String, ServoWiring> servos = new LinkedHashMap<>();
    private final Map<String, I2cWiring> i2cDevices = new LinkedHashMap<>();
    private final Map<String, DigitalWiring> digitals = new LinkedHashMap<>();
    private final Map<String, AnalogWiring> analogs = new LinkedHashMap<>();
    private final Set<Slot> usedMotorPorts = new LinkedHashSet<>();
    private final Set<Slot> usedServoPorts = new LinkedHashSet<>();
    private final Set<Slot> usedDigitalChannels = new LinkedHashSet<>();
    private final Set<Slot> usedAnalogChannels = new LinkedHashSet<>();
    private final List<String> assigned = new ArrayList<>();
    private String embeddedImuName;

    /** What a motor the profile does not describe is assumed to be. */
    public record MotorSpec(double ticksPerRev, double maxRpm) {
        public MotorSpec {
            if (ticksPerRev <= 0 || maxRpm <= 0) {
                throw new IllegalArgumentException("a motor needs a positive resolution and speed: " + ticksPerRev + ", " + maxRpm);
            }
        }
    }

    private WiringTable(List<Hub> hubs, MotorSpec defaultMotor) {
        this.hubs = List.copyOf(hubs);
        this.defaultMotor = defaultMotor;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<Hub> hubs() {
        return hubs;
    }

    @Override
    public synchronized MotorWiring motor(String name) {
        MotorWiring wiring = motors.get(name);
        if (wiring == null) {
            Slot slot = nextFree(usedMotorPorts, MOTOR_PORTS, "motor port", name);
            wiring = new MotorWiring(slot.hub(), slot.index(), defaultMotor.ticksPerRev(), defaultMotor.maxRpm(), false);
            motors.put(name, wiring);
            assigned.add(name);
        }
        return wiring;
    }

    @Override
    public synchronized ServoWiring servo(String name) {
        ServoWiring wiring = servos.get(name);
        if (wiring == null) {
            Slot slot = nextFree(usedServoPorts, SERVO_PORTS, "servo port", name);
            wiring = new ServoWiring(slot.hub(), slot.index(), false);
            servos.put(name, wiring);
            assigned.add(name);
        }
        return wiring;
    }

    @Override
    public synchronized I2cWiring i2c(String name) {
        I2cWiring wiring = i2cDevices.get(name);
        if (wiring == null) {
            wiring = new I2cWiring(hubs.get(0).address(), FIRST_USER_I2C_BUS, false);
            i2cDevices.put(name, wiring);
            assigned.add(name);
        }
        return wiring;
    }

    @Override
    public synchronized I2cWiring imu(String name) {
        I2cWiring placed = i2cDevices.get(name);
        if (placed != null && placed.mapped()) {
            return placed;
        }
        Hub embedded = hubs.stream().filter(Hub::embedded).findFirst().orElseThrow(() -> new IllegalStateException(
                "\"" + name + "\" asks for the IMU of a Control Hub, but this robot has no Control Hub: its hubs are "
                        + hubs.stream().map(Hub::name).toList() + ". Add an IMU to the robot profile to give it one."));
        if (embeddedImuName != null && !embeddedImuName.equals(name)) {
            throw new IllegalStateException("\"" + name + "\" asks for the IMU of the " + embedded.name() + ", but the "
                    + embedded.name() + " has one IMU and \"" + embeddedImuName + "\" already is it. A robot configuration"
                    + " names the IMU once, so robot code has to use the same name for it everywhere.");
        }
        embeddedImuName = name;
        return new I2cWiring(embedded.address(), EMBEDDED_IMU_BUS, false);
    }

    @Override
    public synchronized DigitalWiring digital(String name) {
        DigitalWiring wiring = digitals.get(name);
        if (wiring == null) {
            Slot slot = nextFree(usedDigitalChannels, DIGITAL_CHANNELS, "digital channel", name);
            wiring = new DigitalWiring(slot.hub(), slot.index(), false);
            digitals.put(name, wiring);
            assigned.add(name);
        }
        return wiring;
    }

    @Override
    public synchronized AnalogWiring analog(String name) {
        AnalogWiring wiring = analogs.get(name);
        if (wiring == null) {
            Slot slot = nextFree(usedAnalogChannels, ANALOG_CHANNELS, "analog channel", name);
            wiring = new AnalogWiring(slot.hub(), slot.index(), false);
            analogs.put(name, wiring);
            assigned.add(name);
        }
        return wiring;
    }

    /** The names robot code asked for that the profile did not place, in the order they were asked for. */
    public synchronized List<String> unmappedNames() {
        return List.copyOf(assigned);
    }

    private Slot nextFree(Set<Slot> used, int perHub, String what, String name) {
        for (Hub hub : hubs) {
            for (int index = 0; index < perHub; index++) {
                Slot slot = new Slot(hub.address(), index);
                if (used.add(slot)) {
                    return slot;
                }
            }
        }
        throw new IllegalStateException("\"" + name + "\" needs a free " + what + " but all " + used.size() + " of them are taken");
    }

    // ---------------------------------------------------------------------------------------------

    public static final class Builder {
        private final List<Hub> hubs = new ArrayList<>();
        private MotorSpec defaultMotor = new MotorSpec(537.7, 312);
        private final List<Runnable> placements = new ArrayList<>();
        private WiringTable table;

        /** Declares a hub; the first one declared is the one that gets devices the profile does not place. */
        public Builder hub(int address, String name, boolean parent, boolean embedded) {
            hubs.add(new Hub(address, name, parent, embedded));
            return this;
        }

        public Builder defaultMotor(double ticksPerRev, double maxRpm) {
            defaultMotor = new MotorSpec(ticksPerRev, maxRpm);
            return this;
        }

        public Builder motor(String name, int hub, int port, double ticksPerRev, double maxRpm) {
            placements.add(() -> {
                checkIndex("motor port", port, MOTOR_PORTS);
                claim(table.usedMotorPorts, hub, port, "motor port", name);
                table.motors.put(name, new MotorWiring(hub, port, ticksPerRev, maxRpm, true));
            });
            return this;
        }

        public Builder servo(String name, int hub, int port) {
            placements.add(() -> {
                checkIndex("servo port", port, SERVO_PORTS);
                claim(table.usedServoPorts, hub, port, "servo port", name);
                table.servos.put(name, new ServoWiring(hub, port, true));
            });
            return this;
        }

        public Builder i2c(String name, int hub, int bus) {
            placements.add(() -> {
                checkIndex("I2C bus", bus, I2C_BUSES);
                table.i2cDevices.put(name, new I2cWiring(hub, bus, true));
            });
            return this;
        }

        /**
         * Says that the device the profile places as {@code name} is the IMU of the embedded hub: no other name can be
         * that IMU, whether robot code asks for {@code name} or not.
         */
        public Builder embeddedImu(String name) {
            placements.add(() -> {
                if (table.embeddedImuName != null) {
                    throw new IllegalArgumentException("\"" + name + "\" and \"" + table.embeddedImuName + "\" are both the IMU of the embedded hub");
                }
                table.embeddedImuName = name;
            });
            return this;
        }

        public Builder digital(String name, int hub, int channel) {
            placements.add(() -> {
                checkIndex("digital channel", channel, DIGITAL_CHANNELS);
                claim(table.usedDigitalChannels, hub, channel, "digital channel", name);
                table.digitals.put(name, new DigitalWiring(hub, channel, true));
            });
            return this;
        }

        public Builder analog(String name, int hub, int channel) {
            placements.add(() -> {
                checkIndex("analog channel", channel, ANALOG_CHANNELS);
                claim(table.usedAnalogChannels, hub, channel, "analog channel", name);
                table.analogs.put(name, new AnalogWiring(hub, channel, true));
            });
            return this;
        }

        public WiringTable build() {
            if (hubs.isEmpty()) {
                throw new IllegalStateException("a robot controller has at least its Control Hub");
            }
            table = new WiringTable(hubs, defaultMotor);
            placements.forEach(Runnable::run);
            for (HardwareDirectory.Wiring wiring : allPlaced()) {
                if (hubs.stream().noneMatch(hub -> hub.address() == wiring.hubAddress())) {
                    throw new IllegalArgumentException("a device is wired to hub " + wiring.hubAddress() + ", which the profile does not declare");
                }
            }
            return table;
        }

        private List<HardwareDirectory.Wiring> allPlaced() {
            List<HardwareDirectory.Wiring> all = new ArrayList<>();
            all.addAll(table.motors.values());
            all.addAll(table.servos.values());
            all.addAll(table.i2cDevices.values());
            all.addAll(table.digitals.values());
            all.addAll(table.analogs.values());
            return all;
        }

        private static void checkIndex(String what, int index, int count) {
            if (index < 0 || index >= count) {
                throw new IllegalArgumentException(what + " " + index + " does not exist; there are " + count);
            }
        }

        private void claim(Set<Slot> used, int hub, int index, String what, String name) {
            if (!used.add(new Slot(hub, index))) {
                throw new IllegalArgumentException("\"" + name + "\" is wired to " + what + " " + index + " of hub " + hub + ", which another device already uses");
            }
        }
    }
}
