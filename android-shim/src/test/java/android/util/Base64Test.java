package android.util;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Base64Test {
    @Test
    void theRfcVectorsEncodeWithPaddingAndWithoutWrapping() {
        String[][] vectors = {{"", ""}, {"f", "Zg=="}, {"fo", "Zm8="}, {"foo", "Zm9v"}, {"foob", "Zm9vYg=="}, {"fooba", "Zm9vYmE="}, {"foobar", "Zm9vYmFy"}};
        for (String[] vector : vectors) {
            assertEquals(vector[1], Base64.encodeToString(vector[0].getBytes(US_ASCII), Base64.NO_WRAP), vector[0]);
        }
    }

    @Test
    void theDefaultFlagsEndEveryLineIncludingTheLastInAnLf() {
        assertEquals("Zm9v\n", Base64.encodeToString("foo".getBytes(US_ASCII), Base64.DEFAULT));
        assertEquals("", Base64.encodeToString(new byte[0], Base64.DEFAULT));
        assertEquals("Zm9v\r\n", Base64.encodeToString("foo".getBytes(US_ASCII), Base64.CRLF));
        assertEquals("Zm9v", Base64.encodeToString("foo".getBytes(US_ASCII), Base64.CRLF | Base64.NO_WRAP));
    }

    @Test
    void linesAreSeventySixCharactersLong() {
        String exactlyOneLine = Base64.encodeToString(new byte[57], Base64.DEFAULT);
        String oneLineAndAHalf = Base64.encodeToString(new byte[58], Base64.DEFAULT);

        assertEquals(76 + 1, exactlyOneLine.length());
        assertEquals(76, oneLineAndAHalf.indexOf('\n'));
        assertEquals(76 + 1 + 4 + 1, oneLineAndAHalf.length());
    }

    @Test
    void paddingCanBeOmittedAndTheAlphabetCanBeUrlSafe() {
        byte[] bytes = {(byte) 0xfb, (byte) 0xff};

        assertEquals("+/8=", Base64.encodeToString(bytes, Base64.NO_WRAP));
        assertEquals("+/8", Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.NO_PADDING));
        assertEquals("-_8=", Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE));
    }

    @Test
    void aRangeOfTheInputCanBeEncoded() {
        assertEquals("Zm9v", Base64.encodeToString("xfoox".getBytes(US_ASCII), 1, 3, Base64.NO_WRAP));
    }

    @Test
    void decodingIgnoresWhitespaceAcceptsMissingPaddingAndRoundTrips() {
        assertArrayEquals("foobar".getBytes(US_ASCII), Base64.decode("Zm9v\r\n YmFy\t\n", Base64.DEFAULT));
        assertArrayEquals("fo".getBytes(US_ASCII), Base64.decode("Zm8", Base64.DEFAULT));
        assertArrayEquals(new byte[] {(byte) 0xfb, (byte) 0xff}, Base64.decode("-_8=", Base64.URL_SAFE));
        byte[] everything = new byte[256];
        for (int i = 0; i < everything.length; i++) {
            everything[i] = (byte) i;
        }
        assertArrayEquals(everything, Base64.decode(Base64.encode(everything, Base64.DEFAULT), Base64.DEFAULT));
    }

    @Test
    void decodingRejectsCharactersOutsideTheAlphabet() {
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("Zm9v!", Base64.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("-_8=", Base64.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("Z", Base64.DEFAULT));
    }
}
