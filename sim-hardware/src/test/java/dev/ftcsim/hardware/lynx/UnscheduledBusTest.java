package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HubWire;
import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What a thread that the simulation does not schedule, such as a library's timer, can and cannot do to the hubs. */
class UnscheduledBusTest {

    private static final int GET_ADC = DekaCommand.GET_ADC.number();
    private static final int BATTERY_MONITOR = 13;
    private static final int THREADS = 8;

    private static byte[] batteryRead() {
        return Bytes.of().u8(BATTERY_MONITOR).u8(0).build();
    }

    private static int millivolts(RhspClient.Reply reply) {
        assertEquals(GET_ADC | 0x8000, reply.packetId());
        return ByteBuffer.wrap(reply.payload()).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private static RhspClient unscheduled(LynxBus bus, int address) {
        return new RhspClient(bus, address).unscheduled();
    }

    // ---- the scheduled side keeps working beside them ------------------------------------------------------------

    @Test
    @Timeout(180)
    void eightThreadsMakingEveryQueryThereIsLeaveTheScheduledSideItsOwnAnswers() throws Exception {
        Rig rig = new Rig();
        rig.plugGoBilda(0);
        List<QueryCatalog.Request> requests = QueryCatalog.requests();
        RhspClient[] clients = new RhspClient[THREADS];
        for (int thread = 0; thread < THREADS; thread++) {
            clients[thread] = unscheduled(rig.bus, 173);
        }
        Hammer hammer = new Hammer(THREADS, 200L * requests.size(), (thread, number) -> {
            QueryCatalog.Request request = requests.get((int) ((number + thread) % requests.size()));
            if (QueryCatalog.isBroadcast(request)) {
                HubWire.Exchange exchange = rig.bus.transmitUnscheduled(
                        RhspClient.frame(Datagram.BROADCAST, 1, 0, request.packetId(), request.payload()));
                assertEquals(1, exchange.replies().size(), "the one hub answers discovery");
                assertEquals(0, exchange.latencyNanos());
            } else {
                clients[thread].send(request.packetId(), request.payload());
            }
        });

        hammer.start();
        long calls;
        try {
            for (int step = 0; step < 300; step++) {
                rig.runMillis(1);
                assertEquals(12_000, millivolts(rig.client.send(GET_ADC, batteryRead())));
            }
            hammer.awaitCalls(5_000);
        } finally {
            calls = hammer.finish();
        }

        assertTrue(calls >= THREADS * 200L * requests.size(), "calls: " + calls);
        assertEquals(300 * Rig.MS, rig.hub.nowNanos());
    }

    @Test
    void anUnscheduledQueryLeavesTheTimeAHostWaitingOnAnI2cBusHasLostAsItWas() {
        Rig rig = new Rig();
        rig.hub.attach(0, new MemoryDevice(0x28).with(0x10, 1, 2, 3, 4, 5, 6));
        rig.advanceTo(10 * Rig.MS);
        RhspClient host = rig.client;
        host.ack(DekaCommand.I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(6).u8(0x10).build());

        millivolts(unscheduled(rig.bus, 173).send(GET_ADC, batteryRead()));

        // The host's clock has not moved, so it asks after the transaction only because its last exchange was waited
        // out, and that holds only if the query neither cancelled nor lengthened the wait.
        ByteBuffer result = host.response(DekaCommand.I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build());
        assertEquals(0, result.get());
        assertEquals(6, result.get());
    }

    @Test
    void theRepliesRobotCodeGetsAreNumberedAsIfNobodyElseWereAsking() {
        Rig rig = new Rig();
        RhspClient robot = rig.client;
        RhspClient library = unscheduled(rig.bus, 173);

        int first = robot.standard(StandardCommand.KEEP_ALIVE, Bytes.none()).messageNumber();
        int theLibrarys = library.send(GET_ADC, batteryRead()).messageNumber();
        library.send(GET_ADC, batteryRead());
        int second = robot.standard(StandardCommand.KEEP_ALIVE, Bytes.none()).messageNumber();

        assertEquals(1, first);
        assertEquals(1, theLibrarys);
        assertEquals(2, second);
    }

    // ---- what they get ---------------------------------------------------------------------------------------------

    @Test
    void anUnscheduledCallBeforeAnyScheduledOneWorksWithoutReadingTheClockOrMovingTheHub() {
        LynxBus bus = new LynxBus(() -> {
            throw new AssertionError("the clock was read");
        });
        LynxHub hub = new LynxHub(HubConfig.controlHub(), () -> 12.34, 5_000_000);
        bus.attach(hub);

        HubWire.Exchange exchange = bus.transmitUnscheduled(RhspClient.frame(173, 1, 0, GET_ADC, batteryRead()));

        assertEquals(0, exchange.latencyNanos());
        assertEquals(12_340, millivolts(RhspClient.decode(exchange.replies().get(0), 0)));
        assertEquals(5_000_000, hub.nowNanos());
    }

    @Test
    void theHubsAnswerAsTheyStandAtTheTimeTheScheduledSideLastReachedNotAtTheClock() {
        Rig rig = new Rig();
        StubPlant plant = new StubPlant();
        rig.hub.motorPort(0).connect(plant);
        rig.advanceTo(10 * Rig.MS);
        plant.counts = 250;
        rig.clock.set(50 * Rig.MS);

        RhspClient.Reply reply = unscheduled(rig.bus, 173).send(
                DekaCommand.GET_MOTOR_ENCODER_POSITION.number(), Bytes.of().u8(0).build());

        assertEquals(250, reply.data().getInt(), "the plant is read, but the hub is not run up to the clock");
        assertEquals(10 * Rig.MS, rig.hub.nowNanos());
    }

    @Test
    void aQueryMakesNoNoteOfWhatRobotCodeWouldHaveDoneWrongBeingTheirs() {
        Rig rig = new Rig();
        RhspClient library = unscheduled(rig.bus, 173);

        library.send(GET_ADC, Bytes.of().u8(0).u8(0).build());
        library.send(DekaCommand.GET_SINGLE_DIO_INPUT.number(), Bytes.of().u8(3).build());
        RhspClient.Reply unknown = library.send(0x1FFF, Bytes.none());

        assertEquals(NackReason.PACKET_TYPE_ID_UNKNOWN, unknown.nackReason());
        assertEquals(List.of(), rig.hub.unboundAnalogPorts());
        assertEquals(List.of(), rig.hub.unboundDigitalPorts());
        assertEquals(List.of(), rig.hub.unsupported());

        rig.client.send(GET_ADC, Bytes.of().u8(0).u8(0).build());
        rig.client.send(0x1FFF, Bytes.none());

        assertEquals(List.of(0), rig.hub.unboundAnalogPorts(), "robot code's own read is noted as before");
        assertEquals(1, rig.hub.unsupported().size());
    }

    @Test
    void aQueryIsNotTheHostBeingHeardFromAndAnythingElseIs() {
        long justOver = 2_501 * Rig.MS;
        Rig silent = new Rig(HubConfig.controlHub());
        silent.client.standard(StandardCommand.KEEP_ALIVE, Bytes.none());
        silent.advanceTo(2_000 * Rig.MS);
        unscheduled(silent.bus, 173).send(GET_ADC, batteryRead());
        silent.advanceTo(justOver);

        Rig kept = new Rig(HubConfig.controlHub());
        kept.client.standard(StandardCommand.KEEP_ALIVE, Bytes.none());
        kept.advanceTo(2_000 * Rig.MS);
        unscheduled(kept.bus, 173).standard(StandardCommand.KEEP_ALIVE, Bytes.none());
        kept.advanceTo(justOver);

        assertEquals(ModuleStatus.KEEP_ALIVE_TIMEOUT, status(silent) & ModuleStatus.KEEP_ALIVE_TIMEOUT, "failed safe");
        assertEquals(0, status(kept) & ModuleStatus.KEEP_ALIVE_TIMEOUT, "kept alive by a command that is not a query");
    }

    private static int status(Rig rig) {
        return rig.client.standard(StandardCommand.GET_MODULE_STATUS, Bytes.of().u8(0).build()).payload()[0];
    }

    // ---- what they do ----------------------------------------------------------------------------------------------

    @Test
    void aCommandThatChangesSomethingIsStillExecutedAtTheTimeTheHubIsAt() {
        Rig rig = new Rig();
        StubPlant plant = new StubPlant();
        rig.hub.motorPort(0).connect(plant);
        HubCalls robot = new HubCalls(rig.client);
        robot.setMode(0, HubCalls.OPEN_LOOP, false);
        robot.enable(0, true);
        rig.advanceTo(3 * Rig.MS);

        RhspClient.Reply reply = unscheduled(rig.bus, 173)
                .send(DekaCommand.SET_MOTOR_CONSTANT_POWER.number(), Bytes.of().u8(0).u16(16_384).build());
        rig.advanceTo(5 * Rig.MS);

        assertTrue(reply.isAck());
        assertEquals(16_384 / 32_767.0 * 12.0, plant.volts, 1e-9);
    }

    @Test
    void aStatusReadThatClearsTheEventsIsAChangeAndOneThatDoesNotIsAQuery() {
        Rig rig = new Rig();
        RhspClient library = unscheduled(rig.bus, 173);

        library.standard(StandardCommand.GET_MODULE_STATUS, Bytes.of().u8(0).build());
        assertTrue((status(rig) & ModuleStatus.DEVICE_RESET) != 0, "the reset is still there to be seen");
        library.standard(StandardCommand.GET_MODULE_STATUS, Bytes.of().u8(1).build());

        assertEquals(0, status(rig) & ModuleStatus.DEVICE_RESET, "and now it is gone");
    }

    @Test
    void aDatagramThatCannotBeUnderstoodOrAddressedGetsNoAnswerAndIsRecordedLikeAScheduledOne() {
        Rig rig = new Rig();
        byte[] broken = RhspClient.frame(173, 1, 0, GET_ADC, batteryRead());
        broken[broken.length - 1]++;

        HubWire.Exchange badChecksum = rig.bus.transmitUnscheduled(broken);
        HubWire.Exchange nobody = rig.bus.transmitUnscheduled(RhspClient.frame(9, 1, 0, GET_ADC, batteryRead()));

        assertTrue(badChecksum.replies().isEmpty() && nobody.replies().isEmpty());
        assertEquals(0, badChecksum.latencyNanos() + nobody.latencyNanos());
        assertEquals(
                Set.of(FrameFault.NO_SUCH_MODULE, FrameFault.BAD_CHECKSUM),
                rig.bus.rejectedFrames().stream().map(RejectedFrame::fault).collect(Collectors.toSet()));
    }

    // ---- the bus can be looked at while they work ------------------------------------------------------------------

    @Test
    void aPeripheralIsNeverCalledOnAThreadTheSimulationDoesNotScheduleEvenWhenThatThreadUsesTheI2cBus() throws Exception {
        Rig rig = new Rig();
        Set<Thread> callers = ConcurrentHashMap.newKeySet();
        rig.hub.attach(0, new RegisterFilePeripheral(0x28) {
            @Override
            protected int readRegister(int register, long nowNanos) {
                callers.add(Thread.currentThread());
                return 42;
            }

            @Override
            protected void writeRegister(int register, int value, long nowNanos) {
                callers.add(Thread.currentThread());
            }
        });
        rig.advanceTo(10 * Rig.MS);
        RhspClient library = unscheduled(rig.bus, 173);
        AtomicReference<RhspClient.Reply> collected = new AtomicReference<>();

        onAnotherThread(() -> library.send(
                DekaCommand.I2C_READ_SINGLE_BYTE.number(), Bytes.of().u8(0).u8(0x28).build()));
        rig.runMillis(2);
        onAnotherThread(() -> collected.set(library.send(
                DekaCommand.I2C_READ_STATUS_QUERY.number(), Bytes.of().u8(0).build())));

        assertEquals(Set.of(Thread.currentThread()), callers, "the peripheral was read when the world's time passed");
        ByteBuffer result = collected.get().data();
        assertEquals(0, result.get(), "the transaction went well");
        assertEquals(1, result.get());
        assertEquals(42, result.get(), "and the thread that started it was handed its result");
    }

    private static void onAnotherThread(Runnable work) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @Timeout(120)
    void theDiagnosticsOfAHubCanBeReadWhileAThreadThatChangesThingsUsesItsBus() throws Exception {
        Rig rig = new Rig();
        RhspClient library = unscheduled(rig.bus, 173);
        Hammer hammer = new Hammer(1, 4_000, (thread, number) -> {
            int bus = (int) (number % 4);
            int address = (int) ((number / 4) % 120) + 1;
            RhspClient.Reply reply = library.send(
                    DekaCommand.I2C_READ_SINGLE_BYTE.number(), Bytes.of().u8(bus).u8(address).build());
            // busy until the scheduled side lets time pass, which is an answer like any other
            assertTrue(reply.isAck() || reply.nackReason() == NackReason.I2C_MASTER_BUSY);
        });

        hammer.start();
        try {
            for (int look = 0; look < 4_000; look++) {
                assertTrue(rig.hub.unansweredI2cTargets().size() <= 4 * 120);
                assertEquals(List.of(), rig.hub.unsupported());
                assertEquals(List.of(), rig.hub.unboundMotorPorts());
                if (look % 5 == 0) {
                    rig.runMillis(1);
                }
            }
        } finally {
            hammer.finish();
        }

        assertFalse(rig.hub.unansweredI2cTargets().isEmpty(), "the thread's reads of addresses nobody answers were noted");
    }

    @Test
    @Timeout(120)
    void everythingTheBusReportsCanBeReadWhileEightThreadsUseItAndTheScheduledSideAdvancesIt() throws Exception {
        Rig rig = new Rig();
        rig.plugGoBilda(0);
        RhspClient[] clients = new RhspClient[THREADS];
        for (int thread = 0; thread < THREADS; thread++) {
            clients[thread] = unscheduled(rig.bus, 173);
        }
        Hammer hammer = new Hammer(THREADS, 2_000, (thread, number) -> {
            if (thread == 0 && number % 100 == 0) {
                RhspClient.Reply reply =
                        clients[thread].send(DekaCommand.SET_MOTOR_CONSTANT_POWER.number(), Bytes.of().u8(0).u16(0).build());
                assertTrue(reply.isAck());
            } else {
                assertEquals(12_000, millivolts(clients[thread].send(GET_ADC, batteryRead())));
            }
        });

        hammer.start();
        try {
            for (int look = 0; look < 3_000; look++) {
                assertEquals(1, rig.bus.hubs().size());
                assertEquals(List.of(), rig.bus.rejectedFrames());
                if (look % 50 == 0) {
                    rig.runMillis(1);
                }
            }
        } finally {
            hammer.finish();
        }
    }
}
