package dev.ftcsim.importer.resources;

/** A resource of a library or of the team's project that cannot be processed; the message names the file. */
public final class ResourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ResourceException(String message) {
        super(message);
    }

    public ResourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
