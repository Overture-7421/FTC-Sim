package dev.ftcsim.importer.pack;

/** A prepared program that cannot be used: damaged, altered, incomplete, or made by an incompatible version. */
public final class PackageException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public PackageException(String message) {
        super(message);
    }

    public PackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
