package dev.ftcsim.link.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class FramesTest {

    private static byte[] header(int length) {
        return new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length};
    }

    @Test
    void framesComeOutOfTheStreamAsTheyWentInAndTheStreamEndsWithNull() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] first = {1, 2, 3};
        byte[] second = WireCodec.encode(new Event.Failed("second"));
        Frames.write(wire, first);
        Frames.write(wire, second);
        InputStream in = new ByteArrayInputStream(wire.toByteArray());

        assertArrayEquals(first, Frames.read(in));
        assertArrayEquals(second, Frames.read(in));
        assertNull(Frames.read(in), "a stream that ends between two frames ends cleanly");
    }

    @Test
    void aFrameIsALengthInFourBigEndianBytesAndThePayload() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        Frames.write(wire, new byte[300]);

        byte[] bytes = wire.toByteArray();
        assertEquals(304, bytes.length);
        assertArrayEquals(new byte[] {0, 0, 1, 44}, new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]});
    }

    @Test
    void aStreamThatEndsInsideAFrameIsAWireExceptionThatSaysWhere() {
        assertTrue(assertThrows(WireException.class, () -> Frames.read(new ByteArrayInputStream(new byte[] {0, 0}))).getMessage()
                .contains("after 2 of 4"));

        byte[] cutPayload = {0, 0, 0, 5, 1, 2};
        assertTrue(assertThrows(WireException.class, () -> Frames.read(new ByteArrayInputStream(cutPayload))).getMessage()
                .contains("after 2 of 5"));
    }

    @Test
    void aLengthOutsideTheBoundsIsAWireExceptionBeforeAnyPayloadIsRead() {
        for (int length : new int[] {0, -1, Integer.MIN_VALUE, Protocol.MAX_FRAME_BYTES + 1, Integer.MAX_VALUE}) {
            WireException error =
                    assertThrows(WireException.class, () -> Frames.read(new ByteArrayInputStream(header(length))), "length " + length);

            assertTrue(error.getMessage().contains(Integer.toString(length)), error.getMessage());
        }
    }

    @Test
    void aLengthThatIsWithinTheBoundsButBackedByNothingDoesNotAllocateItsSize() {
        class CountingStream extends InputStream {
            private final byte[] header = header(Protocol.MAX_FRAME_BYTES);
            private int sent;
            private int largestRequest;

            @Override
            public int read() {
                return sent < header.length ? header[sent++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                largestRequest = Math.max(largestRequest, length);
                return super.read(buffer, offset, length);
            }
        }
        CountingStream stream = new CountingStream();

        assertThrows(WireException.class, () -> Frames.read(stream));

        assertTrue(stream.largestRequest < 1 << 20, "the reader asked for " + stream.largestRequest + " bytes at once");
    }

    @Test
    void aFrameWithoutPayloadOrBeyondTheBoundCannotBeWritten() {
        assertThrows(IllegalArgumentException.class, () -> Frames.write(new ByteArrayOutputStream(), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Frames.write(new ByteArrayOutputStream(), new byte[Protocol.MAX_FRAME_BYTES + 1]));
    }
}
