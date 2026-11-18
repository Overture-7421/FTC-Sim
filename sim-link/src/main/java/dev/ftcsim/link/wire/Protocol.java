package dev.ftcsim.link.wire;

/**
 * The constants both ends of the wire agree on: the protocol version, the magic number that starts a handshake and
 * every bound the reader enforces. A reader never allocates more than these bounds allow, whatever a corrupt stream
 * claims.
 */
public final class Protocol {

    /** The ASCII letters FTCS, which start every handshake frame. */
    public static final int MAGIC = 0x46544353;

    /** Changes whenever a message gains, loses or reorders a field. Both ends must speak the same one. */
    public static final int VERSION = 12;

    /** The longest payload of one frame, kind byte included. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** The longest text in one field, in UTF-8 bytes; the writer cuts longer text and ends it with an ellipsis. */
    public static final int MAX_STRING_BYTES = 1024 * 1024;

    /** The most entries any list of a message may have. */
    public static final int MAX_LIST = 100_000;

    /** The longest launch token, in characters. */
    public static final int MAX_TOKEN_CHARS = 128;

    static final int HELLO = 0x01;
    static final int WELCOME = 0x02;
    static final int HANDSHAKE_REFUSED = 0x03;

    static final int OPEN = 0x10;
    static final int INIT = 0x11;
    static final int START = 0x12;
    static final int STOP = 0x13;
    static final int SET_GAMEPAD = 0x14;
    static final int HUMAN_ACTION = 0x15;
    static final int SET_PAUSED = 0x16;
    static final int SET_SPEED = 0x17;
    static final int RESET = 0x18;
    static final int CLOSE = 0x19;
    static final int ARM_MATCH = 0x1A;
    static final int START_MATCH = 0x1B;

    static final int OPENED = 0x40;
    static final int SNAPSHOT = 0x41;
    static final int STATUS = 0x42;
    static final int FAILED = 0x43;
    static final int CATALOGUE = 0x44;
    static final int COMMAND_REFUSED = 0x45;
    static final int LOG = 0x46;

    private Protocol() {}
}
