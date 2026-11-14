package dev.ftcsim.hardware.lynx;

import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;

/**
 * One REV hub module: its identity on the bus, the firmware that answers RHSP datagrams, and the ports a host wires
 * simulated devices into. The firmware runs on a fixed 1 ms tick of simulated time; the module only moves when the
 * host calls {@link #advanceTo}, and answers a datagram at the time it was last advanced to (or at the later time
 * its sender sent it, see {@link #handle(Datagram, long)}). Not thread safe: a hub is used through its
 * {@link LynxBus}, whose lock covers it, apart from the diagnostics lists, which any thread may read.
 *
 * <p>Unconnected ports behave like open circuits and are reported by the diagnostics accessors when robot code
 * uses them.
 */
public final class LynxHub {

    /** The firmware tick: motor control and keep-alive supervision run once per tick. */
    public static final long TICK_NANOS = 1_000_000L;

    private static final double TICK_SECONDS = TICK_NANOS / 1e9;
    private static final int SERVO_COUNT = 6;
    private static final int PWM_COUNT = 4;
    private static final int I2C_BUS_COUNT = 4;

    private final HubConfig config;
    private final DoubleSupplier battery;
    private final Diagnostics diagnostics = new Diagnostics(this::nowNanos);
    private final ModuleStatus status;
    private final Led led = new Led();
    private final MotorBank motors;
    private final PulseBank servos = new PulseBank(SERVO_COUNT, 2, 1, true);
    private final PulseBank pwm = new PulseBank(PWM_COUNT, 0, 0, false);
    private final DigitalBank digital;
    private final I2cBus[] i2c = new I2cBus[I2C_BUS_COUNT];
    private final AdcBank adc;
    private final CommandTable commands = new CommandTable();
    private final MessageNumbers scheduledReplies = new MessageNumbers();
    private final MessageNumbers unscheduledReplies = new MessageNumbers();

    private int address;
    private long now;
    private long commandNanos;
    private long tickIndex;
    private IntPredicate addressAvailable = candidate -> true;

    /**
     * @param batteryVolts the battery voltage the output stage and the ADC see, sampled when needed
     * @param powerOnNanos the simulated time at which the hub powers up
     */
    public LynxHub(HubConfig config, DoubleSupplier batteryVolts, long powerOnNanos) {
        this.config = config;
        this.battery = batteryVolts;
        this.address = config.address();
        this.now = powerOnNanos;
        this.commandNanos = powerOnNanos;
        this.tickIndex = Math.floorDiv(powerOnNanos, TICK_NANOS);
        this.status = new ModuleStatus(config.keepAliveSupervision(), config.signalAttention(), batteryVolts);
        this.motors = new MotorBank(address, diagnostics);
        this.digital = new DigitalBank(address, diagnostics);
        for (int bus = 0; bus < I2C_BUS_COUNT; bus++) {
            i2c[bus] = new I2cBus(bus, diagnostics);
        }
        this.adc = new AdcBank(address, batteryVolts, motors, servos, digital, this::attachedI2cDevices, diagnostics);
        registerCommands();
    }

    public HubConfig config() {
        return config;
    }

    /** The module address, which changes if the host sends {@code SetNewModuleAddress}. */
    public int address() {
        return address;
    }

    public boolean isParent() {
        return config.parent();
    }

    /** The simulated time the hub was last advanced to. */
    public long nowNanos() {
        return now;
    }

    // Wiring

    public Port<MotorPlant> motorPort(int channel) {
        return motors.channel(Indexes.check("motor port", channel, MotorBank.COUNT)).port();
    }

    public Port<DigitalInput> digitalPort(int pin) {
        return digital.port(Indexes.check("digital port", pin, DigitalBank.COUNT));
    }

    public Port<AnalogInput> analogPort(int channel) {
        return adc.port(Indexes.check("analog port", channel, AdcBank.USER_INPUT_COUNT));
    }

    /** Puts a device on one of the hub's I2C buses; two devices cannot share an address on a bus. */
    public void attach(int bus, I2cPeripheral peripheral) {
        i2c[Indexes.check("I2C bus", bus, I2C_BUS_COUNT)].attach(peripheral, false);
    }

    /**
     * Puts a device that is inside the hub's housing on one of its buses, such as the BNO055 of a Control Hub: it
     * answers like any other device, but no port supplies it, so it does not count in the I2C supply current.
     */
    public void attachInternal(int bus, I2cPeripheral peripheral) {
        i2c[Indexes.check("I2C bus", bus, I2C_BUS_COUNT)].attach(peripheral, true);
    }

    /** Whether a device answers at {@code address} on one of the hub's I2C buses. */
    public boolean answersAt(int bus, int address) {
        return i2c[Indexes.check("I2C bus", bus, I2C_BUS_COUNT)].answersAt(address);
    }

    // What the outputs are doing

    public int servoPulseWidthMicros(int channel) {
        return servos.pulseWidth(Indexes.check("servo", channel, SERVO_COUNT));
    }

    public int servoFramePeriodMicros(int channel) {
        return servos.framePeriod(Indexes.check("servo", channel, SERVO_COUNT));
    }

    public boolean servoEnabled(int channel) {
        return servos.enabled(Indexes.check("servo", channel, SERVO_COUNT));
    }

    public int pwmPulseWidthMicros(int channel) {
        return pwm.pulseWidth(Indexes.check("PWM channel", channel, PWM_COUNT));
    }

    public boolean pwmEnabled(int channel) {
        return pwm.enabled(Indexes.check("PWM channel", channel, PWM_COUNT));
    }

    public boolean digitalIsOutput(int pin) {
        return digital.isOutput(Indexes.check("digital port", pin, DigitalBank.COUNT));
    }

    /** The level a digital pin drives; only meaningful while the pin is an output. */
    public boolean digitalOutputLevel(int pin) {
        return digital.driven(Indexes.check("digital port", pin, DigitalBank.COUNT));
    }

    // Diagnostics

    /** Motor ports that robot code drove while nothing was connected to them. */
    public List<Integer> unboundMotorPorts() {
        return diagnostics.unboundMotorPorts();
    }

    /** Digital pins that robot code read individually while nothing was connected to them. */
    public List<Integer> unboundDigitalPorts() {
        return diagnostics.unboundDigitalPorts();
    }

    /** Analog ports that robot code read individually while nothing was connected to them. */
    public List<Integer> unboundAnalogPorts() {
        return diagnostics.unboundAnalogPorts();
    }

    /** I2C addresses that robot code addressed although no peripheral answers there. */
    public List<I2cTarget> unansweredI2cTargets() {
        return diagnostics.unansweredI2cTargets();
    }

    /** Commands and options the emulation answered with a NACK because it does not model them. */
    public List<UnsupportedUse> unsupported() {
        return diagnostics.unsupported();
    }

    // Time and datagrams

    /**
     * Runs the firmware up to the given simulated time: every 1 ms tick in between in order, then the I2C
     * transactions that have finished. Ticks sample the connected plants as they are when this is called.
     */
    public void advanceTo(long nanos) {
        if (nanos < now) {
            throw new IllegalArgumentException("simulated time cannot run backwards: " + nanos + " < " + now);
        }
        long lastTick = Math.floorDiv(nanos, TICK_NANOS);
        for (long tick = tickIndex + 1; tick <= lastTick; tick++) {
            tick(tick * TICK_NANOS);
        }
        tickIndex = lastTick;
        now = nanos;
        for (I2cBus bus : i2c) {
            bus.settle(nanos);
        }
    }

    /** Executes a datagram addressed to this module (or to everyone) and returns the datagrams it sends back. */
    public List<Datagram> handle(Datagram request) {
        return handle(request, now);
    }

    /**
     * Executes a datagram that the host sent at {@code sentNanos}. The firmware's ticks stay at the time the hub was
     * advanced to, which follows the world, but the command runs at the host's time when that is later: a host that
     * has been made to wait for something that takes time, such as an I2C transaction, is past the world's clock.
     */
    public List<Datagram> handle(Datagram request, long sentNanos) {
        boolean broadcast = request.destination() == Datagram.BROADCAST;
        if (!isForThisModule(request, broadcast)) {
            return List.of();
        }
        commandNanos = Math.max(now, sentNanos);
        if (!broadcast) {
            status.noteHostContact(commandNanos);
        }
        return List.of(answer(request, execute(request, commands.find(request.packetId())), scheduledReplies));
    }

    /** What a thread the simulation does not schedule got from the hub, and the command that it ran. */
    record Unscheduled(List<Datagram> replies, CommandTable.Entry command, CommandTable.Effect effect) {}

    /**
     * Executes a datagram from a thread the simulation does not schedule, at the time the hub was last advanced to. A
     * query is answered as the hub stands and leaves no trace in it: it is not the host being heard from, so it does
     * not postpone a keep-alive timeout; nothing it finds is noted in the diagnostics, which are about robot code; and
     * the reply takes its message number from a count of its own, so the replies robot code gets are numbered as
     * they would be without it. Any other command is executed as a real hub would, since the hub cannot tell
     * who sent it; the bus records that it happened.
     */
    Unscheduled handleUnscheduled(Datagram request) {
        boolean broadcast = request.destination() == Datagram.BROADCAST;
        if (!isForThisModule(request, broadcast)) {
            return new Unscheduled(List.of(), null, null);
        }
        CommandTable.Entry command = commands.find(request.packetId());
        CommandTable.Effect effect = command == null ? null : command.effect().of(request.payload());
        commandNanos = now;
        Reply reply;
        if (effect == CommandTable.Effect.MUTATING) {
            if (!broadcast) {
                status.noteHostContact(now);
            }
            reply = execute(request, command);
        } else {
            reply = diagnostics.quietly(() -> execute(request, command));
        }
        return new Unscheduled(List.of(answer(request, reply, unscheduledReplies)), command, effect);
    }

    private boolean isForThisModule(Datagram request, boolean broadcast) {
        return broadcast ? request.packetId() == StandardCommand.DISCOVERY.number() : request.destination() == address;
    }

    /** The commands this module executes, with what each does to it. */
    CommandTable commandTable() {
        return commands;
    }

    /** The bus tells the hub which addresses are free, so that two modules never answer to the same one. */
    void useAddressAuthority(IntPredicate available) {
        addressAvailable = available;
    }

    void changeAddress(int newAddress) {
        if (newAddress != address && !addressAvailable.test(newAddress)) {
            throw new CommandRejected(NackReason.param(0));
        }
        address = newAddress;
    }

    void failSafe() {
        enterFailSafe();
        status.noteFailSafe();
    }

    private void tick(long tickNanos) {
        if (status.keepAliveLapsed(tickNanos)) {
            enterFailSafe();
            status.noteKeepAliveTimeout();
            led.reset();
        }
        motors.tick(battery.getAsDouble(), TICK_SECONDS);
    }

    /** Motors off and servos and PWM outputs disabled; everything else keeps its state. */
    private void enterFailSafe() {
        motors.failSafe();
        servos.disableAll();
        pwm.disableAll();
    }

    private Reply execute(Datagram request, CommandTable.Entry command) {
        if (command == null) {
            diagnostics.unsupported(request.packetId(), "no such command");
            return new Reply.Nack(NackReason.PACKET_TYPE_ID_UNKNOWN);
        }
        try {
            return command.handler().handle(new Params(request.payload()));
        } catch (CommandRejected rejected) {
            return new Reply.Nack(rejected.reason());
        }
    }

    private Datagram answer(Datagram request, Reply reply, MessageNumbers numbers) {
        return switch (reply) {
            case Reply.Ack ack -> respond(request, StandardCommand.ACK.number(), attention(), numbers);
            case Reply.Nack nack ->
                respond(request, StandardCommand.NACK.number(), new byte[] {(byte) nack.reason()}, numbers);
            case Reply.Response response ->
                respond(request, request.packetId() | Datagram.RESPONSE_BIT, response.payload(), numbers);
        };
    }

    /** The payload of an acknowledgement: whether the module has a status the host has not read yet. */
    private byte[] attention() {
        return new byte[] {(byte) (status.attentionRequired() ? 1 : 0)};
    }

    private Datagram respond(Datagram request, int packetId, byte[] payload, MessageNumbers numbers) {
        return new Datagram(request.source(), address, numbers.take(), request.messageNumber(), packetId, payload);
    }

    /** The message numbers a hub gives its replies: 1 to 255, then round again. */
    private static final class MessageNumbers {
        private int next = 1;

        int take() {
            int number = next;
            next = number == 0xFF ? 1 : number + 1;
            return number;
        }
    }

    private int attachedI2cDevices() {
        int total = 0;
        for (I2cBus bus : i2c) {
            total += bus.deviceCount();
        }
        return total;
    }

    private void registerCommands() {
        new StandardCommands(this, status, led, diagnostics).register(commands);
        new ServiceCommands(config).register(commands);
        new MotorCommands(motors).register(commands);
        PulseCommands.register(commands, PulseCommands.SERVO, servos);
        PulseCommands.register(commands, PulseCommands.PWM, pwm);
        new DigitalCommands(digital, this::commandNanos).register(commands);
        new AdcCommands(adc, diagnostics, this::commandNanos).register(commands);
        new BulkInputCommand(digital, motors, adc, this::commandNanos).register(commands);
        new I2cCommands(i2c, diagnostics, this::commandNanos).register(commands);
    }

    /** The time the command being executed runs at; see {@link #handle(Datagram, long)}. */
    private long commandNanos() {
        return commandNanos;
    }
}
