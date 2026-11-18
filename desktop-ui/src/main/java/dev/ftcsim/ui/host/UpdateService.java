package dev.ftcsim.ui.host;

import java.util.function.Consumer;

/**
 * Where the application keeps itself up to date, as the window sees it: whether a newer version has been found, and the
 * person's choice to install it. The window knows nothing about where versions are published, how they are fetched or how
 * they are installed; the application's {@code main} gives it one of these through {@link AppHost#updates()}.
 *
 * <p>Nothing is installed without the person asking: a version that is found is only {@linkplain Kind#AVAILABLE
 * offered}. The status changes on any thread; listeners are told on the thread that changed it, so a listener must not block.
 */
public interface UpdateService {

    /** Where the update is. */
    enum Kind {
        /** Nothing to say: up to date, not checked, or the check found nothing to tell (an offline PC is not a problem). */
        NONE,
        /** A newer version exists and is offered. */
        AVAILABLE,
        /** The installer of the newer version is being fetched and checked. */
        DOWNLOADING,
        /** The installer is ready: the window closes, and the installer replaces this installation and starts it again. */
        RESTARTING,
        /** Fetching or checking the installer failed; {@link Status#message()} says why. The person may try again. */
        FAILED
    }

    /**
     * What there is to say about updates.
     *
     * @param kind    where the update is
     * @param version the newer version, empty for {@link Kind#NONE}
     * @param percent how much of the installer is fetched, 0 to 100, while {@link Kind#DOWNLOADING}
     * @param message why it failed, for {@link Kind#FAILED}; empty otherwise
     */
    record Status(Kind kind, String version, int percent, String message) {
        public static final Status NONE = new Status(Kind.NONE, "", 0, "");

        public static Status available(String version) {
            return new Status(Kind.AVAILABLE, version, 0, "");
        }

        public static Status downloading(String version, int percent) {
            return new Status(Kind.DOWNLOADING, version, Math.max(0, Math.min(100, percent)), "");
        }

        public static Status restarting(String version) {
            return new Status(Kind.RESTARTING, version, 100, "");
        }

        public static Status failed(String version, String message) {
            return new Status(Kind.FAILED, version, 0, message);
        }
    }

    /** An update service that never finds anything: for a host that does not update itself, such as a development build. */
    UpdateService NONE = new UpdateService() {
        @Override
        public Status status() {
            return Status.NONE;
        }

        @Override
        public void addListener(Consumer<Status> listener) {}

        @Override
        public void removeListener(Consumer<Status> listener) {}

        @Override
        public void checkNow() {}

        @Override
        public void install() {}

        @Override
        public void dismiss() {}
    };

    Status status();

    void addListener(Consumer<Status> listener);

    void removeListener(Consumer<Status> listener);

    /** Looks for a newer version, and returns when it has looked. Blocks on the network: never call it on the UI thread. */
    void checkNow();

    /**
     * Fetches the offered version and prepares to install it, and returns when that is done or has failed. The status says
     * which; on {@link Kind#RESTARTING} the window closes itself. Blocks on the network: never call it on the UI thread.
     */
    void install();

    /** The person does not want the offered version now: the offer goes away until the application starts again. */
    void dismiss();
}
