package dev.ftcsim.scenarios;

import com.qualcomm.hardware.limelightvision.Limelight3A;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The public methods of the SDK's {@code Limelight3A} that the simulated driver does not replace. Each one would still
 * try to open a socket and start the real driver's thread, so this must be empty for whichever SDK is on the class path.
 */
public final class LimelightSurface implements Callable<List<String>> {
    @Override
    public List<String> call() throws ClassNotFoundException {
        Class<?> simulated = Class.forName("dev.ftcsim.sdk.hardware.SimLimelight3A");
        List<String> missing = new ArrayList<>();
        for (Method method : Limelight3A.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            try {
                simulated.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException notOverridden) {
                missing.add(method.toString());
            }
        }
        return missing;
    }
}
