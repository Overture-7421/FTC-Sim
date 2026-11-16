package dev.ftcsim.hardware.wiring;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.UnmappedDevice;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each named device is plugged in. The motors are where the robot mapping put them; a motor, servo or pin the
 * mapping does not place gets the next free port, once and for all, and is remembered so the simulator can show it as
 * unbound. The robot's sensors are fixed and found by kind: the IMU of the embedded hub and each {@link Sensor} are
 * bound to the first name robot code asks for them under, and a second name for the same sensor is refused and
 * recorded as unsupported, as is anything else the simulator cannot provide.
 */
public final class WiringTable implements HardwareDirectory {
    private static final int MOTOR_PORTS = 4;
    private static final int SERVO_PORTS = 6;
    private static final int DIGITAL_CHANNELS = 8;
    private static final int ANALOG_CHANNELS = 4;

    private record Slot(int hub, int index) {}

    private final List<Hub> hubs;
    private final MotorSpec defaultMotor;
    private final Map<String, MotorWiring> motors = new LinkedHashMap<>();
    private final Map<String, ServoWiring> servos = new LinkedHashMap<>();
    private final Map<String, DigitalWiring> digitals = new LinkedHashMap<>();
    private final Map<String, AnalogWiring> analogs = new LinkedHashMap<>();
    private final Set<Slot> usedMotorPorts = new LinkedHashSet<>();
    private final Set<Slot> usedServoPorts = new LinkedHashSet<>();
    private final Set<Slot> usedDigitalChannels = new LinkedHashSet<>();
    private final Set<Slot> usedAnalogChannels = new LinkedHashSet<>();
    private final List<UnmappedDevice> assigned = new ArrayList<>();
    private final Map<Sensor, String> sensorNames = new EnumMap<>(Sensor.class);
    private final Set<String> unsupported = new LinkedHashSet<>();
    private String embeddedImuName;

    /** What a motor the mapping does not describe is assumed to be. */
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
            assigned.add(new UnmappedDevice(UnmappedDevice.Kind.MOTOR, name));
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
            assigned.add(new UnmappedDevice(UnmappedDevice.Kind.SERVO, name));
        }
        return wiring;
    }

    @Override
    public synchronized I2cWiring sensor(String name, Sensor kind) {
        String bound = sensorNames.putIfAbsent(kind, name);
        if (bound != null && !bound.equals(name)) {
            throw new IllegalStateException("\"" + name + "\" asks for the " + describe(kind) + ", but the robot has one and \"" + bound
                    + "\" already is it. A robot configuration names a sensor once, so robot code has to use the same name for it everywhere.");
        }
        return new I2cWiring(hubs.get(0).address(), kind.bus(), true);
    }

    @Override
    public synchronized I2cWiring imu(String name) {
        Hub embedded = hubs.stream().filter(Hub::embedded).findFirst().orElseThrow(() -> new IllegalStateException(
                "\"" + name + "\" asks for the IMU of a Control Hub, but this robot has no Control Hub: its hubs are "
                        + hubs.stream().map(Hub::name).toList()));
        if (embeddedImuName != null && !embeddedImuName.equals(name)) {
            throw new IllegalStateException("\"" + name + "\" asks for the IMU of the " + embedded.name() + ", but the "
                    + embedded.name() + " has one IMU and \"" + embeddedImuName + "\" already is it. A robot configuration"
                    + " names the IMU once, so robot code has to use the same name for it everywhere.");
        }
        embeddedImuName = name;
        return new I2cWiring(embedded.address(), EMBEDDED_IMU_BUS, true);
    }

    @Override
    public synchronized void unsupported(String name, String what) {
        unsupported.add("'" + name + "' (" + what + ") is not supported by the simulator, so it is not there");
    }

    @Override
    public synchronized DigitalWiring digital(String name) {
        DigitalWiring wiring = digitals.get(name);
        if (wiring == null) {
            Slot slot = nextFree(usedDigitalChannels, DIGITAL_CHANNELS, "digital channel", name);
            wiring = new DigitalWiring(slot.hub(), slot.index(), false);
            digitals.put(name, wiring);
            assigned.add(new UnmappedDevice(UnmappedDevice.Kind.DIGITAL, name));
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
            assigned.add(new UnmappedDevice(UnmappedDevice.Kind.ANALOG, name));
        }
        return wiring;
    }

    /** The names robot code asked for that the mapping did not place, in the order they were asked for. */
    public synchronized List<String> unmappedNames() {
        return assigned.stream().map(UnmappedDevice::name).toList();
    }

    /** The same, with what kind of device each was asked for. */
    public synchronized List<UnmappedDevice> unmapped() {
        return List.copyOf(assigned);
    }

    /** What robot code asked for that the simulator cannot provide, one sentence each, in the order it was asked for. */
    public synchronized List<String> unsupported() {
        return List.copyOf(unsupported);
    }

    private static String describe(Sensor kind) {
        return switch (kind) {
            case PINPOINT -> "Pinpoint odometry computer";
            case COLOR_SENSOR -> "colour sensor";
        };
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
        throw new IllegalStateException("\"" + name + "\" needs a free " + what + " but all " + used.size() + " of them are taken"
                + (hubs.size() == 1 ? ". The robot has only the " + hubs.get(0).name() + ": add an Expansion Hub to it" : ""));
    }

    // ---------------------------------------------------------------------------------------------

    public static final class Builder {
        private final List<Hub> hubs = new ArrayList<>();
        private MotorSpec defaultMotor = new MotorSpec(537.7, 312);
        private final List<Runnable> placements = new ArrayList<>();
        private WiringTable table;

        /** Declares a hub; the first one declared is the one the robot's sensors are on. */
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
                if (port < 0 || port >= MOTOR_PORTS) {
                    throw new IllegalArgumentException("motor port " + port + " does not exist; there are " + MOTOR_PORTS);
                }
                if (!table.usedMotorPorts.add(new Slot(hub, port))) {
                    throw new IllegalArgumentException(
                            "\"" + name + "\" is wired to motor port " + port + " of hub " + hub + ", which another device already uses");
                }
                if (hubs.stream().noneMatch(declared -> declared.address() == hub)) {
                    throw new IllegalArgumentException("a device is wired to hub " + hub + ", which the robot does not declare");
                }
                table.motors.put(name, new MotorWiring(hub, port, ticksPerRev, maxRpm, true));
            });
            return this;
        }

        public WiringTable build() {
            if (hubs.isEmpty()) {
                throw new IllegalStateException("a robot controller has at least its Control Hub");
            }
            table = new WiringTable(hubs, defaultMotor);
            placements.forEach(Runnable::run);
            return table;
        }
    }
}
