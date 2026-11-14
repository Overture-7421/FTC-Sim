package dev.ftcsim.link.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HandshakeTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private static byte[] helloOf(int version, String token, long pid) {
        WireWriter w = new WireWriter(64);
        w.unsignedByte(Protocol.HELLO);
        w.integer(Protocol.MAGIC);
        w.unsignedShort(version);
        w.string(token);
        w.longInteger(pid);
        return w.finish();
    }

    private static HandshakeException.Reason reasonOf(byte[] hello) {
        return assertThrows(HandshakeException.class, () -> Handshake.readHello(hello, TOKEN)).reason();
    }

    @Test
    void aWorkerWithTheTokenOfTheLaunchAndTheSameVersionIsWelcome() throws WireException {
        Handshake.Hello hello = Handshake.readHello(Handshake.hello(TOKEN, 4242L), TOKEN);
        Handshake.readWelcome(Handshake.welcome());

        assertEquals(new Handshake.Hello(Protocol.VERSION, 4242L), hello);
    }

    @Test
    void aHelloWithAnotherTokenIsRefusedWithoutSayingWhatTheRightOneLooksLike() {
        byte[] hello = Handshake.hello("ffffffffffffffffffffffffffffffff", 1);

        HandshakeException error = assertThrows(HandshakeException.class, () -> Handshake.readHello(hello, TOKEN));

        assertEquals(HandshakeException.Reason.WRONG_TOKEN, error.reason());
        assertTrue(!error.getMessage().contains(TOKEN) && !error.getMessage().contains("ffffffff"), error.getMessage());
    }

    @Test
    void aHelloOfAnotherVersionWithTheRightTokenIsToldApartFromAStranger() {
        HandshakeException error = assertThrows(
                HandshakeException.class, () -> Handshake.readHello(helloOf(Protocol.VERSION + 1, TOKEN, 1), TOKEN));

        assertEquals(HandshakeException.Reason.WRONG_VERSION, error.reason());
        assertTrue(error.getMessage().contains("version " + (Protocol.VERSION + 1)), error.getMessage());
        assertEquals(HandshakeException.Reason.WRONG_TOKEN, reasonOf(helloOf(Protocol.VERSION + 1, "stranger", 1)));
    }

    @Test
    void somethingThatIsNotAHelloAtAllIsAnUnknownPeer() {
        WireWriter wrongMagic = new WireWriter(16);
        wrongMagic.unsignedByte(Protocol.HELLO);
        wrongMagic.integer(0x47455420); // "GET "
        wrongMagic.unsignedShort(1);

        assertEquals(HandshakeException.Reason.UNKNOWN_PEER, reasonOf(wrongMagic.finish()));
        assertEquals(HandshakeException.Reason.UNKNOWN_PEER, reasonOf(WireCodec.encode(new Command.Start())));
    }

    @Test
    void aHelloCutShortOrWithTextBeyondTheBoundIsAWireException() {
        byte[] whole = Handshake.hello(TOKEN, 7);
        for (int length = 0; length < whole.length; length++) {
            byte[] cut = Arrays.copyOf(whole, length);
            assertThrows(WireException.class, () -> Handshake.readHello(cut, TOKEN), "cut to " + length);
        }
        byte[] longToken = helloOf(Protocol.VERSION, "x".repeat(Protocol.MAX_TOKEN_CHARS * 4 + 1), 1);
        assertThrows(WireException.class, () -> Handshake.readHello(longToken, TOKEN));
    }

    @Test
    void theLauncherCanRefuseAWorkerAndGiveItsReason() {
        HandshakeException error =
                assertThrows(HandshakeException.class, () -> Handshake.readWelcome(Handshake.refused("not from the same installation")));

        assertEquals(HandshakeException.Reason.REFUSED, error.reason());
        assertTrue(error.getMessage().contains("not from the same installation"), error.getMessage());
    }

    @Test
    void aWelcomeOfAnotherVersionOrFromAStrangerIsRefusedByTheWorker() {
        WireWriter other = new WireWriter(8);
        other.unsignedByte(Protocol.WELCOME);
        other.integer(Protocol.MAGIC);
        other.unsignedShort(Protocol.VERSION + 1);
        assertEquals(
                HandshakeException.Reason.WRONG_VERSION,
                assertThrows(HandshakeException.class, () -> Handshake.readWelcome(other.finish())).reason());

        assertEquals(
                HandshakeException.Reason.UNKNOWN_PEER,
                assertThrows(HandshakeException.class, () -> Handshake.readWelcome(WireCodec.encode(new Event.Failed("x")))).reason());
    }

    @Test
    void tokensAreRandomPerLaunchAndLongEnoughToGuessNothing() {
        String first = Handshake.newToken();

        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]+"));
        assertNotEquals(first, Handshake.newToken());
    }
}
