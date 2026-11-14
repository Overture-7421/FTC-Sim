package android.content.pm;

/** Knowledge about the applications installed on the device; the simulator supplies the concrete subclass. */
public abstract class PackageManager {

    /** Thrown when a package of the given name is not installed. */
    public static class NameNotFoundException extends Exception {
        public NameNotFoundException(String message) {
            super(message);
        }
    }

    public abstract ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException;
}
