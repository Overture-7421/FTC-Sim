package dev.ftcsim.scenarios;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.util.RobotLog;
import com.qualcomm.robotcore.util.ThreadPool;
import dev.ftcsim.sdk.SdkBootstrap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

/** Brings the SDK singletons up and exercises each of them once. */
public final class SdkBootScenario implements Callable<List<String>> {
    @Override
    public List<String> call() throws Exception {
        List<String> log = new ArrayList<>();
        Path storage = Files.createTempDirectory("ftcsim-sdcard");
        SdkBootstrap.boot(storage, (priority, tag, message) -> log.add(tag + ": " + message));

        RobotLog.dd("probe", "hello %d", 7);

        ExecutorService executor = ThreadPool.newSingleThreadExecutor("probe");
        try {
            log.add("pool=" + executor.submit(() -> 42).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        log.add("first=" + AppUtil.FIRST_FOLDER.toPath().startsWith(storage));
        log.add("hardwareMap=" + (new HardwareMap(null, null) != null));
        return log;
    }
}
