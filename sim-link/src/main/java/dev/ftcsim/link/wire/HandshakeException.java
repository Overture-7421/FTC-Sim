package dev.ftcsim.link.wire;

/** The other end of a new connection is not the worker this launch expects, or does not speak this protocol. */
public final class HandshakeException extends WireException {

    /** Why the handshake failed. */
    public enum Reason {
        /** The first frame is not a handshake of this family of programs. */
        UNKNOWN_PEER,
        /** The hello carries a token other than the one this launch handed to its worker. */
        WRONG_TOKEN,
        /** The peer speaks another version of the protocol. */
        WRONG_VERSION,
        /** The launcher answered that it will not talk to this worker. */
        REFUSED
    }

    private final Reason reason;

    HandshakeException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
