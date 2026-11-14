package dev.ftcsim.hardware.lynx;

import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_CONFIGURE_CHANNEL;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_CONFIGURE_QUERY;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_READ_MULTIPLE_BYTES;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_READ_SINGLE_BYTE;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_READ_STATUS_QUERY;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_WRITE_MULTIPLE_BYTES;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_WRITE_READ_MULTIPLE_BYTES;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_WRITE_SINGLE_BYTE;
import static dev.ftcsim.hardware.lynx.DekaCommand.I2C_WRITE_STATUS_QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.hardware.i2c.RegisterFilePeripheral;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class I2cTransactionsTest {

    private static final long START = 10 * Rig.MS;
    private static final int IN_PROGRESS = NackReason.I2C_OPERATION_IN_PROGRESS;
    private static final int ADDRESS_NOT_ACKNOWLEDGED = 0x01;

    private final Rig rig = Rig.instant();
    private final RhspClient client = rig.client;
    /** Exchanges take the time the bus's latency model says, for the tests where the host polls. */
    private final Rig slow = new Rig();

    @Test
    void aTransactionTakesItsWireTimePlusTheFirmwareAllowanceBeforeItYieldsItsData() {
        rig.hub.attach(0, new MemoryDevice(0x28).with(0x10, 1, 2, 3, 4, 5, 6));
        rig.advanceTo(START);

        client.ack(I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(6).u8(0x10).build());

        // 85 clocks (register write phase 20, read phase 65) at 100 kHz is 850 us, plus 150 us
        rig.advanceTo(START + 999_999);
        assertEquals(IN_PROGRESS, client.nack(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()));
        rig.advanceTo(START + 1_000_000);
        ByteBuffer result = client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build());
        assertEquals(0, result.get());
        assertEquals(6, result.get());
        byte[] data = new byte[6];
        result.get(data);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, data);
    }

    @Test
    void theFastBusClockShortensTheTransaction() {
        rig.hub.attach(0, new MemoryDevice(0x28));
        client.ack(I2C_CONFIGURE_CHANNEL, Bytes.of().u8(0).u8(1).build());
        rig.advanceTo(START);

        client.ack(I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(6).u8(0x10).build());

        rig.advanceTo(START + 362_499);
        assertEquals(IN_PROGRESS, client.nack(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()));
        rig.advanceTo(START + 362_500);
        assertEquals(0, client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()).get());
    }

    @Test
    void aBusRefusesANewTransactionWhileOneIsRunningAndAcceptsOneAfterwards() {
        rig.hub.attach(0, new MemoryDevice(0x28));
        rig.advanceTo(START);
        client.ack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).build());

        rig.advanceTo(START + 100_000);
        assertEquals(NackReason.I2C_MASTER_BUSY, client.nack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).build()));
        assertEquals(NackReason.I2C_MASTER_BUSY, client.nack(I2C_WRITE_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).u8(1).build()));

        rig.advanceTo(START + 400_000);
        client.ack(I2C_WRITE_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).u8(1).build());
    }

    @Test
    void theBusesOfAHubAreIndependentMasters() {
        rig.hub.attach(0, new MemoryDevice(0x28));
        rig.hub.attach(3, new MemoryDevice(0x28));
        rig.advanceTo(START);

        client.ack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).build());
        client.ack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(3).u8(0x28).build());

        rig.advanceTo(START + 1_000_000);
        assertEquals(0, client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(3).build()).get());
        assertEquals(0, client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()).get());
    }

    @Test
    void aWriteReachesTheDeviceWhenItFinishesAndReportsTheBytesWritten() {
        MemoryDevice device = new MemoryDevice(0x29);
        rig.hub.attach(1, device);
        rig.advanceTo(START);

        client.ack(I2C_WRITE_MULTIPLE_BYTES, Bytes.of().u8(1).u8(0x29).u8(3).u8(0x05).u8(0xAA).u8(0xBB).build());

        // 38 clocks at 100 kHz is 380 us, plus 150 us
        rig.advanceTo(START + 529_999);
        assertEquals(IN_PROGRESS, client.nack(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(1).build()));
        assertEquals(List.of(), device.log);
        rig.advanceTo(START + 530_000);
        ByteBuffer result = client.response(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(1).build());
        assertEquals(0, result.get());
        assertEquals(3, result.get());
        assertEquals(
                List.of("write 170 to 5 at " + (START + 530_000), "write 187 to 6 at " + (START + 530_000)), device.log);
    }

    @Test
    void aWriteTakesEffectOnTheDeviceAsTimeAdvancesWithoutAnyStatusQuery() {
        MemoryDevice device = new MemoryDevice(0x29);
        rig.hub.attach(1, device);
        rig.advanceTo(START);

        client.ack(I2C_WRITE_MULTIPLE_BYTES, Bytes.of().u8(1).u8(0x29).u8(2).u8(0x05).u8(0xAA).build());

        // 29 clocks at 100 kHz is 290 us, plus 150 us
        rig.advanceTo(START + 439_999);
        assertEquals(List.of(), device.log);
        rig.advanceTo(START + 440_000);
        assertEquals(List.of("write 170 to 5 at " + (START + 440_000)), device.log);
    }

    @Test
    void aNewCommandReplacesTheResultOfATransactionNobodyAskedAbout() {
        rig.hub.attach(1, new MemoryDevice(0x29).with(7, 0x42));
        rig.advanceTo(START);
        client.ack(I2C_WRITE_SINGLE_BYTE, Bytes.of().u8(1).u8(0x29).u8(0x07).build());
        rig.advanceTo(START + 2_000_000);

        client.ack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(1).u8(0x29).build());

        assertEquals(IN_PROGRESS, client.nack(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(1).build()));
        rig.advanceTo(START + 4_000_000);
        assertEquals(NackReason.I2C_QUERY_MISMATCH, client.nack(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(1).build()));
        ByteBuffer read = client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(1).build());
        assertEquals(0, read.get());
        assertEquals(1, read.get());
        assertEquals(0x42, read.get());
    }

    @Test
    void resultsAreHandedOverOnceAndOnlyToTheMatchingKindOfQuery() {
        rig.hub.attach(0, new MemoryDevice(0x28));
        rig.advanceTo(START);
        assertEquals(NackReason.I2C_NO_RESULTS_PENDING, client.nack(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()));
        assertEquals(NackReason.I2C_NO_RESULTS_PENDING, client.nack(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(0).build()));

        client.ack(I2C_WRITE_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).u8(3).build());
        rig.advanceTo(START + 1_000_000);
        assertEquals(NackReason.I2C_QUERY_MISMATCH, client.nack(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()));
        client.response(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(0).build());
        assertEquals(NackReason.I2C_NO_RESULTS_PENDING, client.nack(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(0).build()));

        client.ack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(0).u8(0x28).build());
        rig.advanceTo(START + 2_000_000);
        assertEquals(NackReason.I2C_QUERY_MISMATCH, client.nack(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(0).build()));
        client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build());
        assertEquals(NackReason.I2C_NO_RESULTS_PENDING, client.nack(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()));
    }

    @Test
    void anAddressNobodyAcknowledgesEndsAfterTheAddressByteAndIsReportedInTheStatus() {
        rig.advanceTo(START);

        client.ack(I2C_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x50).u8(4).build());

        // one address byte and its conditions: 11 clocks at 100 kHz is 110 us, plus 150 us
        rig.advanceTo(START + 259_999);
        assertEquals(IN_PROGRESS, client.nack(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build()));
        rig.advanceTo(START + 260_000);
        ByteBuffer read = client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build());
        assertEquals(ADDRESS_NOT_ACKNOWLEDGED, read.get());
        assertEquals(0, read.get());
        assertEquals(0, read.remaining());

        client.ack(I2C_WRITE_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x50).u8(2).u8(1).u8(2).build());
        rig.advanceTo(START + 1_000_000);
        ByteBuffer write = client.response(I2C_WRITE_STATUS_QUERY, Bytes.of().u8(0).build());
        assertEquals(ADDRESS_NOT_ACKNOWLEDGED, write.get());
        assertEquals(0, write.get());
        assertEquals(List.of(new I2cTarget(0, 0x50)), rig.hub.unansweredI2cTargets());
    }

    @Test
    void theDeviceSeesTheTimeTheTransferEndsNotTheTimeOfTheCommand() {
        slow.hub.attach(2, new ClockDevice(0x68));
        slow.advanceTo(START);

        I2cCalls.Result result = new I2cCalls(slow.timedClient()).readRegisters(2, 0x68, 0x00, 8);

        long seen = ByteBuffer.wrap(result.data()).order(ByteOrder.LITTLE_ENDIAN).getLong();
        // 20 + 83 clocks at 100 kHz, plus 150 us, after the command arrived at START
        assertEquals(START + 1_180_000, seen);
    }

    @Test
    void aHostThatCannotLetTheClockMoveWhilePollingStillGetsEveryTransactionDone() {
        slow.hub.attach(0, new MemoryDevice(0x28).with(0x10, 1, 2, 3, 4, 5, 6));
        slow.advanceTo(START);
        I2cCalls robot = new I2cCalls(slow.client);

        I2cCalls.Result read = robot.readRegisters(0, 0x28, 0x10, 6);
        I2cCalls.Result write = robot.writeAndWait(0, 0x28, 0x20, new byte[] {9});
        I2cCalls.Result readAgain = robot.readRegisters(0, 0x28, 0x20, 1);

        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, read.data());
        assertEquals(0, write.status());
        assertArrayEquals(new byte[] {9}, readAgain.data());
        assertEquals(START, slow.clock.get(), "the clock never moved");
        assertEquals(START, slow.hub.nowNanos(), "and the hub's ticks did not run ahead of it");
    }

    @Test
    void aPollingHostWhoseClockStandsStillWaitsAsLongAsOneWhoseClockMoves() {
        MemoryDevice device = new MemoryDevice(0x28);
        for (int i = 0; i < 100; i++) {
            device.with(i, i + 1);
        }
        slow.hub.attach(0, device);
        slow.advanceTo(START);

        int withClockMoving = new I2cCalls(slow.timedClient()).readRegisters(0, 0x28, 0, 100).polls();
        slow.advanceTo(slow.clock.get() + 100 * Rig.MS);
        int withClockFrozen = new I2cCalls(slow.client).readRegisters(0, 0x28, 0, 100).polls();

        assertTrue(withClockMoving > 3, "polls: " + withClockMoving);
        assertEquals(withClockMoving, withClockFrozen, "the exchanges of a host whose clock stands still count all the same");
    }

    @Test
    void theSdksTwoReadSequencesGetTheSameBytesAndTheOldOneNeedsTwoCommands() {
        slow.hub.attach(0, new MemoryDevice(0x28).with(0x10, 9, 8, 7, 6, 5, 4, 3, 2));
        I2cCalls calls = new I2cCalls(slow.timedClient());

        I2cCalls.Result current = calls.readRegisters(0, 0x28, 0x12, 5);
        I2cCalls.Result legacy = calls.readRegistersTheOldWay(0, 0x28, 0x12, 5);
        I2cCalls.Result single = calls.readRegisters(0, 0x28, 0x15, 1);
        I2cCalls.Result legacySingle = calls.readRegistersTheOldWay(0, 0x28, 0x15, 1);

        assertArrayEquals(new byte[] {7, 6, 5, 4, 3}, current.data());
        assertArrayEquals(current.data(), legacy.data());
        assertArrayEquals(new byte[] {4}, single.data());
        assertArrayEquals(single.data(), legacySingle.data());
        assertEquals(0, current.status());
    }

    @Test
    void aLongReadOutlastsTheExchangeLatencyAndIsPolledUntilItIsDone() {
        byte[] contents = new byte[100];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = (byte) (i + 1);
        }
        MemoryDevice device = new MemoryDevice(0x28);
        for (int i = 0; i < contents.length; i++) {
            device.with(i, contents[i]);
        }
        slow.hub.attach(0, device);
        long before = slow.clock.get();

        I2cCalls.Result result = new I2cCalls(slow.timedClient()).readRegisters(0, 0x28, 0, 100);

        assertArrayEquals(contents, result.data());
        assertTrue(result.polls() > 3, "polls: " + result.polls());
        // 931 clocks at 100 kHz plus the allowance
        assertTrue(slow.clock.get() - before >= 9_460_000);
    }

    @Test
    void aWriteWaitedForWithWrittenGetsItsStatusFromTheQuery() {
        MemoryDevice device = new MemoryDevice(0x29);
        slow.hub.attach(0, device);

        I2cCalls.Result result =
                new I2cCalls(slow.timedClient()).writeAndWait(0, 0x29, 0x20, new byte[] {1, 2, 3});

        assertEquals(0, result.status());
        assertEquals(3 + 1, result.data()[0]);
        assertEquals(3, device.log.size());
    }

    @Test
    void commandsRefuseAddressesCountsAndBusesOutsideTheirRanges() {
        assertEquals(0, client.nack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(4).u8(0x28).build()));
        assertEquals(1, client.nack(I2C_READ_SINGLE_BYTE, Bytes.of().u8(0).u8(128).build()));
        for (int count : new int[] {0, 1, 101}) {
            assertEquals(2, client.nack(I2C_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(count).build()), "read " + count);
        }
        assertEquals(1, client.nack(I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(128).u8(1).u8(0).build()));
        for (int count : new int[] {0, 101}) {
            assertEquals(2, client.nack(I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(count).u8(0).build()));
            assertEquals(2, client.nack(I2C_WRITE_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(count).raw(new byte[count]).build()));
        }
        assertEquals(3, client.nack(I2C_WRITE_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(4).u8(1).u8(2).build()));
    }

    @Test
    void theOneByteWriteReadAndTheHundredByteWriteAreInRange() {
        rig.hub.attach(0, new MemoryDevice(0x28));

        client.ack(I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(1).u8(0).build());
        rig.advanceTo(Rig.MS);
        client.response(I2C_READ_STATUS_QUERY, Bytes.of().u8(0).build());
        client.ack(I2C_WRITE_MULTIPLE_BYTES, Bytes.of().u8(0).u8(0x28).u8(100).raw(new byte[100]).build());
    }

    @Test
    void theBusSpeedIsStoredAndOnlyTheTwoSpeedsOfTheHardwareAreAccepted() {
        assertEquals(0, client.response(I2C_CONFIGURE_QUERY, Bytes.of().u8(2).build()).get());

        client.ack(I2C_CONFIGURE_CHANNEL, Bytes.of().u8(2).u8(1).build());
        assertEquals(1, client.response(I2C_CONFIGURE_QUERY, Bytes.of().u8(2).build()).get());
        assertEquals(0, client.response(I2C_CONFIGURE_QUERY, Bytes.of().u8(1).build()).get());

        assertEquals(1, client.nack(I2C_CONFIGURE_CHANNEL, Bytes.of().u8(2).u8(2).build()));
        assertEquals(1, client.nack(I2C_CONFIGURE_CHANNEL, Bytes.of().u8(2).u8(3).build()));
        assertEquals(1, client.response(I2C_CONFIGURE_QUERY, Bytes.of().u8(2).build()).get());
        assertEquals(1, rig.hub.unsupported().size());
        assertEquals(2, rig.hub.unsupported().get(0).count());
    }

    @Test
    void twoDevicesCannotShareAnAddressOnABusButCanOnDifferentBuses() {
        rig.hub.attach(0, new MemoryDevice(0x28));
        rig.hub.attach(1, new MemoryDevice(0x28));

        assertThrows(IllegalStateException.class, () -> rig.hub.attach(0, new MemoryDevice(0x28)));
        assertThrows(IllegalArgumentException.class, () -> rig.hub.attach(4, new MemoryDevice(0x29)));
    }

    /** Registers 0 to 7 read as the simulated time, in nanoseconds, at the start of the read. */
    private static final class ClockDevice extends RegisterFilePeripheral {
        private long latched;

        ClockDevice(int address) {
            super(address);
        }

        @Override
        protected void beforeRead(int firstRegister, int count, long nowNanos) {
            latched = nowNanos;
        }

        @Override
        protected int readRegister(int register, long nowNanos) {
            return (int) (latched >> (8 * register)) & 0xFF;
        }
    }
}
