package dev.ftcsim.sdk;

import com.qualcomm.robotcore.eventloop.opmode.OpModeRegister;
import com.qualcomm.robotcore.util.ClassUtil;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.firstinspires.ftc.ftccommon.internal.AnnotatedHooksClassFilter;
import org.firstinspires.ftc.robotcore.internal.opmode.AnnotatedOpModeClassFilter;
import org.firstinspires.ftc.robotcore.internal.opmode.ClassManager;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMetaAndClass;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMetaAndInstance;
import org.firstinspires.ftc.robotcore.internal.opmode.RegisteredOpModes;

/**
 * The OpModes of the robot program, found and registered by the SDK itself: the SDK's class manager walks every
 * class of the program (see {@code dalvik.system.DexFile} in the Android shim) and its annotation filter applies
 * {@code @TeleOp}, {@code @Autonomous}, {@code @Disabled}, naming and duplicate rules.
 */
final class OpModeCatalog {
    private OpModeCatalog() {}

    /**
     * Scans the program and fills the SDK's registries of OpModes and of library hooks; the scan loads classes but
     * does not initialise them. Every class filter has to be registered before the scan, which the SDK runs once. The
     * classes of the SDK and of libraries that cannot be loaded are reported by one line, see {@link ScanLog}.
     */
    static void register(ScanLog scanLog) {
        ClassManager classes = ClassManager.getInstance();
        classes.registerFilter(AnnotatedHooksClassFilter.getInstance());
        classes.registerFilter(AnnotatedOpModeClassFilter.getInstance());
        scanLog.beginScan();
        try {
            classes.processAllClasses();
        } finally {
            scanLog.endScan();
        }
        RegisteredOpModes.getInstance().registerAllOpModes((OpModeRegister) manager -> {});
    }

    /** The driver-selectable OpModes, ordered by group and name; the SDK's own system OpModes are not listed. */
    static List<OpModeDescriptor> list() {
        RegisteredOpModes registry = RegisteredOpModes.getInstance();
        Map<String, String> classNames = classNames(registry);
        List<OpModeDescriptor> opModes = new ArrayList<>();
        for (OpModeMeta meta : registry.getOpModes()) {
            OpModeKind kind = kindOf(meta);
            if (kind != null) {
                String group = OpModeMeta.DefaultGroup.equals(meta.group) ? "" : meta.group;
                opModes.add(new OpModeDescriptor(meta.name, group, kind, classNames.getOrDefault(meta.name, "")));
            }
        }
        opModes.sort(Comparator.comparing(OpModeDescriptor::group).thenComparing(OpModeDescriptor::name));
        return opModes;
    }

    private static OpModeKind kindOf(OpModeMeta meta) {
        return switch (meta.flavor.name()) {
            case "TELEOP" -> OpModeKind.TELEOP;
            case "AUTONOMOUS" -> OpModeKind.AUTONOMOUS;
            default -> null;
        };
    }

    /** The registry knows the class behind each name but offers no accessor, and instantiating OpModes to ask would run user code. */
    private static Map<String, String> classNames(RegisteredOpModes registry) {
        Map<String, String> names = new HashMap<>();
        registry.lockOpModesWhile(() -> {
            for (Object entry : registryMap(registry, "opModeClasses").values()) {
                OpModeMetaAndClass registered = (OpModeMetaAndClass) entry;
                names.put(registered.meta.name, registered.clazz.getName());
            }
            for (Object entry : registryMap(registry, "opModeInstances").values()) {
                OpModeMetaAndInstance registered = (OpModeMetaAndInstance) entry;
                names.put(registered.meta.name, registered.instance.getClass().getName());
            }
        });
        return names;
    }

    private static Map<?, ?> registryMap(RegisteredOpModes registry, String fieldName) {
        Field field = ClassUtil.getDeclaredField(RegisteredOpModes.class, fieldName);
        if (field == null) {
            throw new IllegalStateException("The SDK's OpMode registry has no field '" + fieldName + "'; this SDK version is not supported yet");
        }
        try {
            return (Map<?, ?>) field.get(registry);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read the SDK's OpMode registry", e);
        }
    }
}
