package dev.ftcsim.hardware.lynx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DatagramTest {

    @Test
    void encodesTheAcknowledgementExampleDocumentedInTheSdk() {
        Datagram ack = new Datagram(0x00, 0x01, 0x07, 0x6B, 0x7F01, new byte[] {1});

        assertArrayEquals(HexFormat.of().parseHex("444b0c000001076b017f018f"), ack.encode());
    }

    @Test
    void parsesWhatItEncodesWhateverTheFieldsAndPayloadLength() {
        for (int length : new int[] {0, 1, 100, 300}) {
            byte[] payload = new byte[length];
            for (int i = 0; i < length; i++) {
                payload[i] = (byte) (i * 7 + 3);
            }
            Datagram original = new Datagram(173, 0, 255, 1, 0x9034, payload);

            Datagram parsed = parse(original.encode());

            assertEquals(173, parsed.destination());
            assertEquals(0, parsed.source());
            assertEquals(255, parsed.messageNumber());
            assertEquals(1, parsed.referenceNumber());
            assertEquals(0x9034, parsed.packetId());
            assertTrue(parsed.isResponse());
            assertEquals(0x1034, parsed.commandNumber());
            assertArrayEquals(payload, parsed.payload());
        }
    }

    @Test
    void agreesWithTheIndependentlyBuiltFrame() {
        byte[] payload = {1, 2, 3, (byte) 0xFF};

        byte[] byHand = RhspClient.frame(173, 9, 0, 0x1022, payload);

        assertArrayEquals(byHand, new Datagram(173, 0, 9, 0, 0x1022, payload).encode());
    }

    @Test
    void doesNotLetACallerChangeAPayloadAfterwards() {
        byte[] payload = {1, 2};
        Datagram datagram = new Datagram(1, 0, 1, 0, 0x7F04, payload);

        payload[0] = 99;
        datagram.payload()[1] = 99;

        assertArrayEquals(new byte[] {1, 2}, datagram.payload());
    }

    @Test
    void refusesFieldsThatDoNotFitTheirBytes() {
        assertThrows(IllegalArgumentException.class, () -> new Datagram(256, 0, 1, 0, 0x7F04, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Datagram(1, 0, 1, 0, 0x10000, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Datagram(1, 0, -1, 0, 0x7F04, new byte[0]));
    }

    static Stream<Arguments> damagedFrames() {
        return Stream.of(
                damage("shorter than the fixed part", frame -> Arrays.copyOf(frame, 10), FrameFault.TOO_SHORT),
                damage("empty", frame -> new byte[0], FrameFault.TOO_SHORT),
                damage("first frame byte wrong", frame -> with(frame, 0, 0x45), FrameFault.BAD_FRAME_BYTES),
                damage("second frame byte wrong", frame -> with(frame, 1, 0x4C), FrameFault.BAD_FRAME_BYTES),
                damage("length field too small", frame -> with(frame, 2, frame.length - 1), FrameFault.LENGTH_MISMATCH),
                damage("length field too large", frame -> with(frame, 2, frame.length + 1), FrameFault.LENGTH_MISMATCH),
                damage("trailing byte", frame -> Arrays.copyOf(frame, frame.length + 1), FrameFault.LENGTH_MISMATCH),
                damage("truncated", frame -> Arrays.copyOf(frame, frame.length - 1), FrameFault.LENGTH_MISMATCH),
                damage("payload bit flipped", frame -> with(frame, 11, frame[11] ^ 0x01), FrameFault.BAD_CHECKSUM),
                damage("address changed", frame -> with(frame, 4, 174), FrameFault.BAD_CHECKSUM),
                damage(
                        "checksum off by one",
                        frame -> with(frame, frame.length - 1, frame[frame.length - 1] + 1),
                        FrameFault.BAD_CHECKSUM));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damagedFrames")
    void rejectsFramesThatARealHubWouldIgnore(String name, UnaryOperator<byte[]> damage, FrameFault expected) {
        byte[] damaged = damage.apply(RhspClient.frame(173, 1, 0, 0x7F04, new byte[] {1, 2, 3}));

        MalformedFrameException thrown = assertThrows(MalformedFrameException.class, () -> Datagram.parse(damaged));

        assertEquals(expected, thrown.fault());
    }

    private static Arguments damage(String name, UnaryOperator<byte[]> damage, FrameFault expected) {
        return Arguments.of(name, damage, expected);
    }

    private static byte[] with(byte[] frame, int index, int value) {
        byte[] copy = frame.clone();
        copy[index] = (byte) value;
        return copy;
    }

    private static Datagram parse(byte[] frame) {
        try {
            return Datagram.parse(frame);
        } catch (MalformedFrameException e) {
            throw new AssertionError(e);
        }
    }
}
