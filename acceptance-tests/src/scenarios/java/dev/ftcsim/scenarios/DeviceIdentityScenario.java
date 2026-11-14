package dev.ftcsim.scenarios;

import dev.ftcsim.sdk.SdkBootstrap;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.threeten.bp.Instant;
import org.threeten.bp.ZoneId;

/** Boots the SDK and asks it, and the time library it uses, about the device it believes it runs on. */
public final class DeviceIdentityScenario implements Callable<List<String>> {
    @Override
    public List<String> call() throws Exception {
        SdkBootstrap.boot(Files.createTempDirectory("ftcsim-sdcard"), (priority, tag, message) -> {});

        ZoneId newYork = ZoneId.of("America/New_York");
        return List.of(
                "robotController=" + AppUtil.getInstance().isRobotController(),
                "driverStation=" + AppUtil.getInstance().isDriverStation(),
                "newYorkInWinter=" + newYork.getRules().getOffset(Instant.parse("2026-01-15T12:00:00Z")),
                "newYorkInSummer=" + newYork.getRules().getOffset(Instant.parse("2026-07-15T12:00:00Z")));
    }
}
