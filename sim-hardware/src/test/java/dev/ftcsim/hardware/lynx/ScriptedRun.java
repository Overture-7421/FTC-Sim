package dev.ftcsim.hardware.lynx;

import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * A scripted run of the scheduled side: two hubs on one bus, two motors, an I2C device, ports, a battery that wanders
 * and an SDK-like mix of commands, with every reply and everything the plants were told written down. Whatever
 * else is going on around it, a run must write down the same transcript.
 */
final class ScriptedRun {

    static final int STEPS = 3000;

    /** What happened in a run: every reply, every call a plant received, and where everything ended up. */
    record Transcript(List<String> exchanges, List<String> plantCalls, List<String> finalState) {}

    private final AtomicLong clock = new AtomicLong();
    private volatile double battery = 12.6;
    final LynxBus bus = new LynxBus(clock::get);
    private final LynxHub parent = new LynxHub(HubConfig.controlHub(), () -> battery, 0);
    private final LynxHub child = new LynxHub(HubConfig.expansionHub(2, false, "child"), () -> battery, 0);
    private final DcMotorPlant lift = DcMotorPlant.goBilda435(0.004);
    private final DcMotorPlant wheel = DcMotorPlant.goBilda435(0.001);
    private final List<String> exchanges = new ArrayList<>();
    private final HubCalls parentCalls = new HubCalls(new RhspClient(bus, 173));
    private final RhspClient parentWire = new RhspClient(bus, 173);
    private final RhspClient childWire = new RhspClient(bus, 2);
    private final Random random = new Random(20_260_113L);

    Transcript run() {
        return run(step -> {});
    }

    /** Runs the script; {@code beforeStep} is called at the start of every one of its {@link #STEPS} steps. */
    Transcript run(IntConsumer beforeStep) {
        bus.attach(parent);
        bus.attach(child);
        parent.motorPort(0).connect(wheel);
        child.motorPort(1).connect(lift);
        child.attach(2, new Gyro());
        parent.analogPort(1).connect(nanos -> 1.0 + Math.sin(nanos * 1e-9));
        child.digitalPort(4).connect(nanos -> (nanos / 7_000_000L) % 2 == 0);

        for (int step = 0; step < STEPS; step++) {
            beforeStep.accept(step);
            script(step);
            wheel.step(1e-3);
            lift.step(1e-3);
            clock.addAndGet(Rig.MS);
            battery = 12.6 - 0.8 * random.nextDouble();
            bus.advanceTo(clock.get());
        }

        List<String> state = new ArrayList<>();
        state.add("wheel " + wheel.encoderCounts() + " " + wheel.encoderCountsPerSecond());
        state.add("lift " + lift.encoderCounts() + " " + lift.encoderCountsPerSecond());
        state.add("servo " + parent.servoPulseWidthMicros(0) + " " + parent.servoEnabled(0));
        state.add("times " + parent.nowNanos() + " " + child.nowNanos());
        state.add("unbound " + parent.unboundMotorPorts() + parent.unboundDigitalPorts() + parent.unboundAnalogPorts()
                + child.unboundMotorPorts() + child.unboundDigitalPorts() + child.unboundAnalogPorts());
        parent.unsupported().forEach(use -> state.add("unsupported " + use));
        child.unansweredI2cTargets().forEach(target -> state.add("unanswered " + target));
        bus.rejectedFrames().forEach(rejected -> state.add("rejected " + rejected));
        List<String> plantCalls = new ArrayList<>(wheel.calls());
        plantCalls.addAll(lift.calls());
        return new Transcript(exchanges, plantCalls, state);
    }

    private void script(int step) {
        switch (step) {
            case 0 -> {
                record(parentWire, 0x7F07, "DEKA\0".getBytes(StandardCharsets.UTF_8));
                record(parentWire, 0x7F04, Bytes.none());
                record(childWire, 0x7F07, "DEKA\0".getBytes(StandardCharsets.UTF_8));
                record(parentWire, 0x1000 + DekaCommand.READ_VERSION_STRING.ordinal(), Bytes.none());
                record(parentWire, 0x7F08, Bytes.none());
            }
            case 5 -> {
                deka(parentWire, DekaCommand.SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(0).u8(1).u8(0).build());
                deka(parentWire, DekaCommand.SET_MOTOR_TARGET_VELOCITY, Bytes.of().u8(0).u16(1200).build());
                deka(parentWire, DekaCommand.SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).u8(1).build());
                deka(childWire, DekaCommand.SET_MOTOR_TARGET_POSITION, Bytes.of().u8(1).i32(900).u16(5).build());
                deka(childWire, DekaCommand.SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(1).u8(2).u8(0).build());
                deka(childWire, DekaCommand.SET_MOTOR_TARGET_VELOCITY, Bytes.of().u8(1).u16(1000).build());
                deka(childWire, DekaCommand.SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(1).u8(1).build());
                deka(parentWire, DekaCommand.SET_SERVO_CONFIGURATION, Bytes.of().u8(0).u16(20_000).build());
                deka(parentWire, DekaCommand.SET_SERVO_PULSE_WIDTH, Bytes.of().u8(0).u16(1700).build());
                deka(parentWire, DekaCommand.SET_SERVO_ENABLE, Bytes.of().u8(0).u8(1).build());
                deka(parentWire, DekaCommand.SET_SERVO_ENABLE, Bytes.of().u8(5).u8(1).build());
                deka(parentWire, DekaCommand.GET_ADC, Bytes.of().u8(13).u8(1).build());
                deka(childWire, DekaCommand.IS_MOTOR_AT_TARGET, Bytes.of().u8(0).build());
            }
            case 900 -> {
                deka(parentWire, DekaCommand.SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(0).u8(0).u8(1).build());
                deka(parentWire, DekaCommand.SET_MOTOR_CONSTANT_POWER, Bytes.of().u8(0).u16(-14_000).build());
                deka(childWire, DekaCommand.SET_MOTOR_TARGET_POSITION, Bytes.of().u8(1).i32(-300).u16(5).build());
            }
            case 1500 -> {
                parentCalls.setPidf(0, HubCalls.VELOCITY, 1.1, 0.11, 0, 12.0, HubCalls.PIDF);
                deka(parentWire, DekaCommand.SET_MOTOR_CHANNEL_MODE, Bytes.of().u8(0).u8(1).u8(0).build());
                deka(parentWire, DekaCommand.SET_MOTOR_TARGET_VELOCITY, Bytes.of().u8(0).u16(-1800).build());
                deka(childWire, DekaCommand.RESET_MOTOR_ENCODER, Bytes.of().u8(1).build());
            }
            case 2200 -> deka(parentWire, DekaCommand.SET_MOTOR_CHANNEL_ENABLE, Bytes.of().u8(0).u8(0).build());
            case 2400 -> record(parentWire, 0x7F05, Bytes.none());
            default -> routine(step);
        }
    }

    /** What an OpMode loop does every few milliseconds: bulk reads, sensor reads and a few writes. */
    private void routine(int step) {
        if (step % 10 == 0) {
            deka(parentWire, DekaCommand.GET_BULK_INPUT_DATA, Bytes.none());
            deka(childWire, DekaCommand.GET_BULK_INPUT_DATA, Bytes.none());
        }
        if (step % 25 == 3) {
            deka(childWire, DekaCommand.I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(2).u8(0x68).u8(6).u8(0x10).build());
        }
        if (step % 25 == 8) {
            deka(childWire, DekaCommand.I2C_READ_STATUS_QUERY, Bytes.of().u8(2).build());
        }
        if (step % 25 == 12) {
            deka(childWire, DekaCommand.I2C_READ_SINGLE_BYTE, Bytes.of().u8(2).u8(0x70).build());
        }
        if (step % 25 == 18) {
            deka(childWire, DekaCommand.I2C_READ_STATUS_QUERY, Bytes.of().u8(2).build());
        }
        if (step % 40 == 7) {
            deka(parentWire, DekaCommand.GET_ADC, Bytes.of().u8(1).u8(0).build());
            deka(childWire, DekaCommand.GET_SINGLE_DIO_INPUT, Bytes.of().u8(4).build());
            deka(childWire, DekaCommand.GET_ADC, Bytes.of().u8(9).u8(0).build());
        }
        if (step % 97 == 11) {
            deka(parentWire, DekaCommand.SET_SERVO_PULSE_WIDTH, Bytes.of().u8(0).u16(900 + random.nextInt(1200)).build());
            record(parentWire, 0x7F04, Bytes.none());
        }
    }

    private void deka(RhspClient wire, DekaCommand command, byte[] payload) {
        record(wire, 0x1000 + command.ordinal(), payload);
    }

    private void record(RhspClient wire, int packetId, byte[] payload) {
        RhspClient.Reply reply = wire.send(packetId, payload);
        exchanges.add(clock.get() + " " + HexFormat.of().formatHex(reply.payload()) + " " + hex(reply.packetId())
                + " " + reply.messageNumber() + " " + reply.referenceNumber() + " " + reply.latencyNanos());
    }

    private static String hex(int packetId) {
        return String.format("%04x", packetId);
    }

    /** An I2C device at 0x68 whose registers move with the simulated time. */
    private static final class Gyro extends RegisterFilePeripheral {
        private long snapshot;

        Gyro() {
            super(0x68);
        }

        @Override
        protected void beforeRead(int firstRegister, int count, long nowNanos) {
            snapshot = nowNanos;
        }

        @Override
        protected int readRegister(int register, long nowNanos) {
            return (int) (snapshot / 1000 >> (4 * (register & 0x0F))) & 0xFF;
        }
    }
}
