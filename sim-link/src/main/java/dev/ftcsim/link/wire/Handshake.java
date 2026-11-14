package dev.ftcsim.link.wire;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The first frames of a connection. The launcher listens on the loopback interface and hands its worker a random token
 * of this launch; the worker connects back and says hello with the protocol version and that token, and the launcher
 * answers welcome, or refused with the reason. Anything else that connects to the port has no token and is dropped.
 *
 * <p>The layout of the hello is the one thing that never changes between protocol versions, so that a launcher can
 * always tell a worker of another version from a stranger.
 */
public final class Handshake {

    private static final int MAX_REASON_BYTES = 4096;

    /**
     * What a worker says about itself.
     *
     * @param version the protocol version it speaks
     * @param pid     its process id, for diagnostics
     */
    public record Hello(int version, long pid) {}

    private Handshake() {}

    /** A new random token, as lower-case hexadecimal. */
    public static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The payload of the frame a worker sends first. */
    public static byte[] hello(String token, long pid) {
        WireWriter w = new WireWriter(64);
        w.unsignedByte(Protocol.HELLO);
        w.integer(Protocol.MAGIC);
        w.unsignedShort(Protocol.VERSION);
        w.string(token);
        w.longInteger(pid);
        return w.finish();
    }

    /**
     * Checks a hello against the token of this launch.
     *
     * @throws HandshakeException if it is not a hello of this family of programs, carries another token, or speaks
     *                            another protocol version, checked in that order so that a stranger is never told more
     *                            than that it was not expected
     * @throws WireException      if it is cut short or out of bounds
     */
    public static Hello readHello(byte[] frame, String expectedToken) throws WireException {
        WireReader r = new WireReader(frame);
        if (r.unsignedByte() != Protocol.HELLO || r.integer() != Protocol.MAGIC) {
            throw new HandshakeException(HandshakeException.Reason.UNKNOWN_PEER, "the peer did not start with a hello of this program family");
        }
        int version = r.unsignedShort();
        String token = r.string("the token", Protocol.MAX_TOKEN_CHARS * 4);
        long pid = r.longInteger();
        r.end();
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new HandshakeException(HandshakeException.Reason.WRONG_TOKEN, "the hello carries a token that is not the one of this launch");
        }
        if (version != Protocol.VERSION) {
            throw new HandshakeException(
                    HandshakeException.Reason.WRONG_VERSION,
                    "the worker speaks protocol version " + version + " but this program speaks version " + Protocol.VERSION
                            + "; the worker and the program that started it are not from the same installation");
        }
        return new Hello(version, pid);
    }

    /** The payload of the launcher's answer to a hello it accepts. */
    public static byte[] welcome() {
        WireWriter w = new WireWriter(8);
        w.unsignedByte(Protocol.WELCOME);
        w.integer(Protocol.MAGIC);
        w.unsignedShort(Protocol.VERSION);
        return w.finish();
    }

    /** The payload of the launcher's answer to a hello it does not accept, with the reason for the worker's log. */
    public static byte[] refused(String reason) {
        WireWriter w = new WireWriter(64);
        w.unsignedByte(Protocol.HANDSHAKE_REFUSED);
        w.string(reason);
        return w.finish();
    }

    /**
     * Checks the launcher's answer.
     *
     * @throws HandshakeException if the launcher refused the worker or speaks another protocol version
     * @throws WireException      if the answer is cut short or is not an answer at all
     */
    public static void readWelcome(byte[] frame) throws WireException {
        WireReader r = new WireReader(frame);
        int kind = r.unsignedByte();
        if (kind == Protocol.HANDSHAKE_REFUSED) {
            String reason = r.string("the reason", MAX_REASON_BYTES);
            r.end();
            throw new HandshakeException(HandshakeException.Reason.REFUSED, "the launcher refused this worker: " + reason);
        }
        if (kind != Protocol.WELCOME || r.integer() != Protocol.MAGIC) {
            throw new HandshakeException(HandshakeException.Reason.UNKNOWN_PEER, "the peer did not answer with a welcome of this program family");
        }
        int version = r.unsignedShort();
        r.end();
        if (version != Protocol.VERSION) {
            throw new HandshakeException(
                    HandshakeException.Reason.WRONG_VERSION,
                    "the launcher speaks protocol version " + version + " but this worker speaks version " + Protocol.VERSION);
        }
    }
}
