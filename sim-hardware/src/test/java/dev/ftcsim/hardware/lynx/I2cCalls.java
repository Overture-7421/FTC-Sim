package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** The I2C call sequences of the SDK's synchronous device classes, over a client that lets time pass. */
final class I2cCalls {

    /** What a polled transaction returned. */
    record Result(int status, byte[] data, int polls) {}

    private static final int MAX_POLLS = 200;

    private final RhspClient client;

    I2cCalls(RhspClient client) {
        this.client = client;
    }

    /** LynxI2cDeviceSynchV2.readTimeStamped: one write-read command, then status queries until it is done. */
    Result readRegisters(int bus, int address, int register, int count) {
        sendUntilAccepted(DekaCommand.I2C_WRITE_READ_MULTIPLE_BYTES, Bytes.of().u8(bus).u8(address).u8(count).u8(register).build());
        return pollRead(bus);
    }

    /** LynxI2cDeviceSynchV1.readTimeStamped: write the register byte, then read, then poll. */
    Result readRegistersTheOldWay(int bus, int address, int register, int count) {
        sendUntilAccepted(DekaCommand.I2C_WRITE_SINGLE_BYTE, Bytes.of().u8(bus).u8(address).u8(register).build());
        if (count == 1) {
            sendUntilAccepted(DekaCommand.I2C_READ_SINGLE_BYTE, Bytes.of().u8(bus).u8(address).build());
        } else {
            sendUntilAccepted(DekaCommand.I2C_READ_MULTIPLE_BYTES, Bytes.of().u8(bus).u8(address).u8(count).build());
        }
        return pollRead(bus);
    }

    /** A register write followed by the status query that I2cWaitControl.WRITTEN adds. */
    Result writeAndWait(int bus, int address, int register, byte[] data) {
        byte[] payload = Bytes.of().u8(register).raw(data).build();
        sendUntilAccepted(
                DekaCommand.I2C_WRITE_MULTIPLE_BYTES,
                Bytes.of().u8(bus).u8(address).u8(payload.length).raw(payload).build());
        for (int polls = 1; polls <= MAX_POLLS; polls++) {
            RhspClient.Reply reply = client.deka(DekaCommand.I2C_WRITE_STATUS_QUERY, Bytes.of().u8(bus).build());
            if (reply.isNack()) {
                assertEquals(NackReason.I2C_OPERATION_IN_PROGRESS, reply.nackReason());
                continue;
            }
            ByteBuffer data2 = reply.data();
            int status = data2.get() & 0xFF;
            return new Result(status, new byte[] {data2.get()}, polls);
        }
        throw new AssertionError("the write never finished");
    }

    private Result pollRead(int bus) {
        for (int polls = 1; polls <= MAX_POLLS; polls++) {
            RhspClient.Reply reply = client.deka(DekaCommand.I2C_READ_STATUS_QUERY, Bytes.of().u8(bus).build());
            if (reply.isNack()) {
                assertEquals(NackReason.I2C_OPERATION_IN_PROGRESS, reply.nackReason());
                continue;
            }
            ByteBuffer data = reply.data();
            int status = data.get() & 0xFF;
            byte[] bytes = new byte[data.get() & 0xFF];
            data.get(bytes);
            return new Result(status, bytes, polls);
        }
        throw new AssertionError("the read never finished");
    }

    /** LynxI2cDeviceSynch.sendI2cTransaction: a busy master is retried. */
    private void sendUntilAccepted(DekaCommand command, byte[] payload) {
        for (int attempts = 0; attempts < MAX_POLLS; attempts++) {
            RhspClient.Reply reply = client.deka(command, payload);
            if (reply.isAck()) {
                return;
            }
            assertTrue(
                    reply.nackReason() == NackReason.I2C_MASTER_BUSY
                            || reply.nackReason() == NackReason.I2C_OPERATION_IN_PROGRESS,
                    command + " refused with " + reply.nackReason());
        }
        throw new AssertionError(command + " was never accepted: " + Arrays.toString(payload));
    }
}
