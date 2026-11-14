package dev.ftcsim.hardware.i2c;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegisterFilePeripheralTest {

    private final Memory device = new Memory(0x3C);

    @Test
    void theFirstByteOfAWriteSelectsTheRegisterAndTheRestFillConsecutiveOnes() {
        device.write(new byte[] {0x10, 1, 2, 3}, 0);

        device.write(new byte[] {0x10}, 0);
        assertArrayEquals(new byte[] {1, 2, 3, 0}, device.read(4, 0));
    }

    @Test
    void readsStartAtThePointerAndTheNextReadContinuesWhereTheLastEnded() {
        device.write(new byte[] {0x00, 10, 11, 12, 13, 14}, 0);

        device.write(new byte[] {0x01}, 0);
        assertArrayEquals(new byte[] {11, 12}, device.read(2, 0));
        assertArrayEquals(new byte[] {13, 14}, device.read(2, 0));
    }

    @Test
    void theWritePointerFollowsTheDataItWrote() {
        device.write(new byte[] {0x20, 7, 8}, 0);

        assertArrayEquals(new byte[] {0, 0}, device.read(2, 0));
    }

    @Test
    void thePointerWrapsAroundAfterRegister255() {
        device.write(new byte[] {(byte) 0xFE, 1, 2, 3, 4}, 0);

        device.write(new byte[] {(byte) 0xFE}, 0);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, device.read(4, 0));
        device.write(new byte[] {0x00}, 0);
        assertArrayEquals(new byte[] {3, 4}, device.read(2, 0));
    }

    @Test
    void aWriteOfOnlyARegisterNumberMovesThePointerAndAnEmptyWriteChangesNothing() {
        device.write(new byte[] {0x40, 5}, 0);
        device.write(new byte[] {0x40}, 0);
        device.write(new byte[0], 0);

        assertArrayEquals(new byte[] {5}, device.read(1, 0));
    }

    @Test
    void writeReadIsAWriteFollowedByARead() {
        device.write(new byte[] {0x05, 9, 8, 7}, 0);

        assertArrayEquals(new byte[] {8, 7}, device.writeRead(new byte[] {0x06}, 2, 0));
    }

    @Test
    void aModelCanComputeRegistersFromTheTimeOfTheReadAndKeepMultiByteValuesCoherent() {
        Counter counter = new Counter(0x11);

        // the two reads straddle a tick of the counter's microsecond clock, yet each read is self-consistent
        long first = 0x01020304L * 1000;
        long second = 0x01020305L * 1000;

        assertArrayEquals(new byte[] {4, 3, 2, 1}, counter.writeRead(new byte[] {0x20}, 4, first));
        assertArrayEquals(new byte[] {5, 3, 2, 1}, counter.writeRead(new byte[] {0x20}, 4, second));
        assertEquals(List.of("4 from 32 at " + first, "4 from 32 at " + second), counter.reads);
    }

    @Test
    void theOrdinaryRegistersOfAModelWithComputedOnesStillBehaveAsMemory() {
        Counter counter = new Counter(0x11);

        counter.write(new byte[] {0x00, 42}, 0);

        assertArrayEquals(new byte[] {42}, counter.writeRead(new byte[] {0x00}, 1, 0));
    }

    @Test
    void addressesAreSevenBit() {
        assertThrows(IllegalArgumentException.class, () -> new Memory(128));
        assertThrows(IllegalArgumentException.class, () -> new Memory(-1));
        assertEquals(127, new Memory(127).address());
    }

    private static final class Memory extends RegisterFilePeripheral {
        Memory(int address) {
            super(address);
        }
    }

    /** Registers 0x20 to 0x23 read as the little-endian microsecond clock sampled once per read. */
    private static final class Counter extends RegisterFilePeripheral {
        final List<String> reads = new ArrayList<>();
        private long microseconds;

        Counter(int address) {
            super(address);
        }

        @Override
        protected void beforeRead(int firstRegister, int count, long nowNanos) {
            microseconds = nowNanos / 1000;
            reads.add(count + " from " + firstRegister + " at " + nowNanos);
        }

        @Override
        protected int readRegister(int register, long nowNanos) {
            if (register >= 0x20 && register < 0x24) {
                return (int) (microseconds >> (8 * (register - 0x20))) & 0xFF;
            }
            return super.readRegister(register, nowNanos);
        }
    }
}
