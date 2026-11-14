package dev.ftcsim.runtime.fixture;

import java.util.concurrent.Callable;

/** Loads native libraries the way the FTC SDK does in its static initialisers. */
@SuppressWarnings("restricted")
public final class NativeLibraryProbe implements Callable<String> {
    @Override
    public String call() {
        System.loadLibrary("RobotCore");
        System.load("C:\\natives\\other.dll");
        return "loaded";
    }
}
