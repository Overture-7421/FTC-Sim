package dev.ftcsim.simulator.profile;

/** A robot profile that cannot be read or does not describe a robot that can exist; the message says where. */
public final class ProfileException extends IllegalArgumentException {

    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
