package dev.ftcsim.link.wire;

import java.io.IOException;

/** Bytes that are not a valid message of the protocol: truncated, out of bounds, unknown or inconsistent. */
public class WireException extends IOException {

    public WireException(String message) {
        super(message);
    }

    public WireException(String message, Throwable cause) {
        super(message, cause);
    }
}
