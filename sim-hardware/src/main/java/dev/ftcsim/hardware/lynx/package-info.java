/**
 * The REV Robotics hub as the FTC SDK sees it on its serial bus: the REV Hub Serial Protocol (RHSP) codec, the
 * hub's firmware behaviour behind it, and a bus that routes datagrams between the robot controller and its hubs.
 * Time never comes from a clock in here; it arrives through {@code advanceTo} and the bus's clock supplier.
 *
 * <p>A host wires a hub like this:
 *
 * <pre>{@code
 * AtomicLong simNanos = new AtomicLong();                         // the kernel's simulated clock
 * LynxBus bus = new LynxBus(simNanos::get);
 * HubConfig config = HubConfig.controlHub().withKeepAliveSupervision(false).withAttentionSignalling(false);
 * LynxHub hub = new LynxHub(config, () -> batteryVolts, simNanos.get());
 * bus.attach(hub);
 * hub.motorPort(0).connect(leftWheel);                            // a MotorPlant
 * hub.analogPort(1).connect(nanos -> 1.65);                       // an AnalogInput
 * hub.digitalPort(3).connect(nanos -> !touchPressed);             // a DigitalInput
 * hub.attach(1, imu);                                             // an I2cPeripheral on I2C bus 1
 * // every world step, after the plants have moved:  simNanos.addAndGet(stepNanos); bus.advanceTo(simNanos.get());
 * // the robot side hands every datagram it sends to bus.transmit(bytes) and delivers the replies after the latency
 * // a thread nobody schedules, such as a library's timer, uses bus.transmitUnscheduled(bytes), at no cost in time
 * }</pre>
 *
 * <p><b>Threads and the bus.</b> The simulation runs one thread at a time: the simulator thread moves the clock and
 * calls {@code advanceTo}, and the robot thread runs between its moves and calls {@code transmit}. That is the
 * scheduled side. Any other thread that reaches the hubs (a library's own timer reading the battery voltage every few
 * seconds of real time, say) is unscheduled: it runs whenever the operating system lets it, and it must neither move
 * simulated time nor disturb the scheduled side's own exchanges.
 *
 * <ul>
 *   <li><b>One lock.</b> Every method of {@link dev.ftcsim.hardware.lynx.LynxBus} takes the same fair lock, so a
 *       datagram or an {@code advanceTo} is never seen or interrupted half done, and a thread that arrives while
 *       another is inside waits for its turn in order of arrival. The hubs, their I2C engines, the peripherals on
 *       them and the plants and sources wired to them are called with that lock held, from whichever thread is
 *       inside; they must not wait for the thread that advances the bus. The hubs' own methods are not locked: reach
 *       a hub through its bus, or from the thread that advances it. The exception is each hub's diagnostics lists,
 *       which the UI may read at any time.
 *   <li><b>{@code transmitUnscheduled}</b> answers as the hubs stand at the time the scheduled side last reached. It
 *       does not read the clock, does not run the hubs forward, leaves the hold-off that I2C polling relies on alone,
 *       and its exchanges take no time. It works before the first scheduled call.
 *   <li><b>Queries</b> (every command that only reads and starts nothing) are answered normally and leave no trace: they
 *       are not the host being heard from, so they do not postpone a keep-alive timeout; nothing they find is noted in
 *       the diagnostics; and their replies are numbered from a count of their own, so the replies the robot gets are
 *       numbered as if nobody else were asking.
 *   <li><b>Anything else</b> (a command that writes, resets, starts an I2C transaction, takes a finished transaction's
 *       result, or asks for the module's events to be cleared) is still executed, as a real hub would, at the time the
 *       hubs are at, because the hub cannot tell who sent it.
 *   <li><b>Which is which</b> is a property of the command, given where it is registered: the
 *       {@code CommandTable} takes a classification with every command and a test insists that every command has
 *       one.
 *   <li>A query reads the plants and sources it needs from the calling thread, as the scheduled side does. The
 *       simulation's own plants hold plain fields that the simulator thread updates outside the lock, so such a read
 *       can be a step stale, never worse.
 * </ul>
 */
package dev.ftcsim.hardware.lynx;
