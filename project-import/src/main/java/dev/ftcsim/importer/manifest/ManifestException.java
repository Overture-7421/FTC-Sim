package dev.ftcsim.importer.manifest;

/** A manifest that cannot be read, or that this version of FTC-Sim does not accept; the message says where and why. */
public final class ManifestException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public ManifestException(String message) {
        super(message);
    }

    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
