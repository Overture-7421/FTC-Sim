package dev.ftcsim.runtime;

import java.util.List;

/**
 * The reflection calls where Android's ART runtime is more permissive than HotSpot, redirected to {@code ReflectHooks}
 * for robot-world classes: {@link java.lang.reflect.Field}{@code .set*} (ART allows writing {@code static final} fields
 * after {@code setAccessible(true)}) and {@link java.lang.reflect.Method#invoke} (ART allows invoking a {@code public}
 * method declared on a non-public class).
 */
final class ReflectRules {
    static final String HOOKS = "dev/ftcsim/contracts/hooks/ReflectHooks";

    private static final String FIELD = "java/lang/reflect/Field";
    private static final String METHOD = "java/lang/reflect/Method";

    private ReflectRules() {}

    static List<RewriteRule> all() {
        return List.of(
                fieldSetter("set", "Ljava/lang/Object;"),
                fieldSetter("setBoolean", "Z"),
                fieldSetter("setByte", "B"),
                fieldSetter("setChar", "C"),
                fieldSetter("setShort", "S"),
                fieldSetter("setInt", "I"),
                fieldSetter("setLong", "J"),
                fieldSetter("setFloat", "F"),
                fieldSetter("setDouble", "D"),
                new RewriteRule(
                        METHOD,
                        "invoke",
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                        HOOKS,
                        "invoke",
                        "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                        true));
    }

    private static RewriteRule fieldSetter(String name, String valueType) {
        return new RewriteRule(
                FIELD,
                name,
                "(Ljava/lang/Object;" + valueType + ")V",
                HOOKS,
                name,
                "(Ljava/lang/reflect/Field;Ljava/lang/Object;" + valueType + ")V",
                true);
    }
}
