package dev.ftcsim.hardware.lynx;

/** Why a hub or the bus ignored a datagram without answering it. */
public enum FrameFault {
    /** Fewer bytes than the fixed part of a datagram. */
    TOO_SHORT,
    /** The datagram does not begin with the frame bytes 0x44 0x4B. */
    BAD_FRAME_BYTES,
    /** The packet length field disagrees with the number of bytes received. */
    LENGTH_MISMATCH,
    /** The additive checksum does not match. */
    BAD_CHECKSUM,
    /** A well-formed datagram addressed to a module that is not on the bus. */
    NO_SUCH_MODULE
}
