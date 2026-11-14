package dev.ftcsim.sdk;

import android.os.Environment;
import android.os.SystemProperties;
import android.util.Log;
import com.qualcomm.robotcore.hardware.configuration.LynxConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

/**
 * Brings the SDK's process-wide singletons up on a desktop JVM. The order matters: the SDK caches what it
 * learns about the device and roots its folder tree at class-initialisation time, so identity and storage
 * must be chosen before any SDK class is touched.
 */
public final class SdkBootstrap {
    /** The simulated robot controller is a REV Control Hub on the oldest OS the SDK accepts. */
    private static final Map<String, String> CONTROL_HUB_PROPERTIES = Map.of(
            "persist.ftcandroid.serialasusb", "true",
            "ro.controlhub.os.version", LynxConstants.MINIMUM_LEGAL_CH_OS_VERSION_STRING,
            "ro.controlhub.os.versionnum", Integer.toString(LynxConstants.MINIMUM_LEGAL_CH_OS_VERSION_CODE));

    private SdkBootstrap() {}

    /** Boots a robot controller that has no robot program installed. */
    public static void boot(Path storageRoot, Log.Sink log) {
        boot(storageRoot, List.of(), log);
    }

    /**
     * @param storageRoot directory standing in for the robot controller's external storage
     * @param program     classes directories and jars of the robot program, which the SDK scans for OpModes
     * @param log         receives every line the SDK and robot libraries log
     */
    public static void boot(Path storageRoot, List<Path> program, Log.Sink log) {
        SystemProperties.configure(CONTROL_HUB_PROPERTIES);
        Environment.setExternalStorageDirectory(storageRoot.toFile());
        Log.setSink(log);
        AppUtil.onApplicationStart(new SimApplication(applicationPackage(program)));
    }

    private static Path applicationPackage(List<Path> program) {
        try {
            return ApplicationPackage.create(program);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the classes of the robot program", e);
        }
    }
}
