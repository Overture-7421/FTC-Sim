package android.os;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * Reports changes to a directory's entries or to a single file, from a watcher thread. Android builds this on
 * inotify; the desktop's watch service offers fewer event kinds, so only {@link #CREATE}, {@link #DELETE},
 * {@link #MODIFY} and {@link #DELETE_SELF} are ever delivered. As with inotify, a path that does not exist cannot
 * be watched, and watching it silently does nothing.
 */
public abstract class FileObserver {
    public static final int ACCESS = 0x00000001;
    public static final int MODIFY = 0x00000002;
    public static final int ATTRIB = 0x00000004;
    public static final int CLOSE_WRITE = 0x00000008;
    public static final int CLOSE_NOWRITE = 0x00000010;
    public static final int OPEN = 0x00000020;
    public static final int MOVED_FROM = 0x00000040;
    public static final int MOVED_TO = 0x00000080;
    public static final int CREATE = 0x00000100;
    public static final int DELETE = 0x00000200;
    public static final int DELETE_SELF = 0x00000400;
    public static final int MOVE_SELF = 0x00000800;
    public static final int ALL_EVENTS = 0x00000fff;

    private final Path path;
    private final int mask;
    private WatchService watcher;

    public FileObserver(String path) {
        this(path, ALL_EVENTS);
    }

    public FileObserver(String path, int mask) {
        this.path = Path.of(path);
        this.mask = mask;
    }

    /** Called on the watcher thread; {@code path} names the changed entry of a watched directory, and is null for a watched file. */
    public abstract void onEvent(int event, String path);

    public synchronized void startWatching() {
        boolean watchesDirectory = Files.isDirectory(path);
        Path directory = watchesDirectory ? path : directoryOfExistingFile();
        if (watcher != null || directory == null) {
            return;
        }
        try {
            WatchService service = FileSystems.getDefault().newWatchService();
            directory.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            watcher = service;
            Thread thread = new Thread(() -> deliver(service, watchesDirectory), "FileObserver " + path);
            thread.setDaemon(true);
            thread.start();
        } catch (IOException e) {
            // As on Android, a watch that cannot be established is not reported
        }
    }

    private Path directoryOfExistingFile() {
        return Files.exists(path) ? path.toAbsolutePath().getParent() : null;
    }

    public synchronized void stopWatching() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                // the watch is gone either way
            }
            watcher = null;
        }
    }

    private void deliver(WatchService service, boolean watchesDirectory) {
        try {
            while (true) {
                WatchKey key = service.take();
                for (WatchEvent<?> change : key.pollEvents()) {
                    if (change.context() instanceof Path entry) {
                        report(change.kind(), entry.toString(), watchesDirectory);
                    }
                }
                if (!key.reset()) {
                    report(StandardWatchEventKinds.ENTRY_DELETE, null, true);
                    return;
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException stopped) {
            // stopWatching() closed the service
        }
    }

    private void report(WatchEvent.Kind<?> kind, String entry, boolean watchesDirectory) {
        if (watchesDirectory) {
            int event = entry == null ? DELETE_SELF : eventOf(kind);
            deliverIfWanted(event, entry);
        } else if (entry == null || entry.equals(path.getFileName().toString())) {
            deliverIfWanted(kind == StandardWatchEventKinds.ENTRY_DELETE ? DELETE_SELF : eventOf(kind), null);
        }
    }

    private void deliverIfWanted(int event, String entry) {
        if ((event & mask) != 0) {
            onEvent(event, entry);
        }
    }

    private static int eventOf(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return CREATE;
        }
        return kind == StandardWatchEventKinds.ENTRY_DELETE ? DELETE : MODIFY;
    }
}
