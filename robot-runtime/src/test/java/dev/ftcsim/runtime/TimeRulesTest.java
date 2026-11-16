package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.contracts.hooks.TimeHooks;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps the table of time rules, the JDK and the hooks in step, so a JDK that gains a way to the clock is noticed. */
class TimeRulesTest {
    private static final List<RewriteRule> TIME = TimeRules.all();
    private static final Set<String> MEMBERS = TIME.stream()
            .map(rule -> rule.owner() + '.' + rule.name() + rule.descriptor())
            .collect(Collectors.toSet());

    /** Members of the JDK that read the clock and that the simulator leaves alone, and why. */
    private static final Set<String> LEFT_ALONE = Set.of(
            // These take what they are given: the program's own date and time.
            "java/util/Date.<init>(J)V",
            "java/util/Date.<init>(Ljava/lang/String;)V",
            "java/util/Date.<init>(III)V",
            "java/util/Date.<init>(IIIII)V",
            "java/util/Date.<init>(IIIIII)V",
            "java/util/GregorianCalendar.<init>(III)V",
            "java/util/GregorianCalendar.<init>(IIIII)V",
            "java/util/GregorianCalendar.<init>(IIIIII)V");

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static MethodType typeOf(String descriptor) {
        return MethodType.fromMethodDescriptorString(descriptor, TimeRulesTest.class.getClassLoader());
    }

    @Test
    void everyRuleCallsAHookThatExists() throws ReflectiveOperationException {
        for (RewriteRule rule : TIME) {
            MethodHandles.publicLookup().findStatic(TimeHooks.class, rule.hookName(), typeOf(rule.hookDescriptor()));
        }
    }

    @Test
    void everyRuleNamesAJdkMemberThatExists() throws ReflectiveOperationException {
        for (RewriteRule rule : TIME) {
            Class<?> owner = Class.forName(rule.owner().replace('/', '.'));
            MethodType type = typeOf(rule.descriptor());
            if (rule.isConstructor()) {
                owner.getConstructor(type.parameterArray());
            } else {
                Method method = owner.getMethod(rule.name(), type.parameterArray());
                assertEquals(type.returnType(), method.getReturnType(), rule.key());
                assertTrue(Modifier.isStatic(method.getModifiers()), rule.key() + " is not static");
            }
        }
    }

    @Test
    void everySeededConstructorAndItsSupplierExist() throws ReflectiveOperationException {
        for (RewriteRules.SeededConstructor seeded : TimeRules.seeded()) {
            Class<?> owner = Class.forName(seeded.owner().replace('/', '.'));
            owner.getConstructor(typeOf(seeded.descriptor()).parameterArray());
            Constructor<?> longer = owner.getConstructor(typeOf(seeded.seededDescriptor()).parameterArray());
            assertEquals(long.class, longer.getParameterTypes()[longer.getParameterCount() - 1], seeded.key());
            MethodHandles.publicLookup().findStatic(Hooks.class, seeded.hookName(), MethodType.methodType(long.class));
        }
    }

    @Test
    void everyConstructorOfADateThatTakesNothingFromTheProgramIsRedirected() {
        for (Class<?> type : List.of(Date.class, GregorianCalendar.class)) {
            for (Constructor<?> constructor : type.getConstructors()) {
                String member = internalName(type) + ".<init>"
                        + MethodType.methodType(void.class, constructor.getParameterTypes()).toMethodDescriptorString();
                assertTrue(MEMBERS.contains(member) || LEFT_ALONE.contains(member), member + " reads the clock unnoticed");
            }
        }
    }

    @Test
    void everyWayToACalendarIsRedirected() {
        List<String> missing = new ArrayList<>();
        for (Method method : java.util.Calendar.class.getMethods()) {
            boolean asks = Modifier.isStatic(method.getModifiers()) && method.getName().startsWith("getInstance");
            String member = internalName(java.util.Calendar.class) + '.' + method.getName()
                    + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
            if (asks && !MEMBERS.contains(member)) {
                missing.add(member);
            }
        }
        assertEquals(List.of(), missing);
    }

    /** Every {@code now()} the JDK has, found where the JDK keeps its classes. */
    @Test
    void everyNowOfEveryClassOfJavaTimeIsRedirected() throws IOException, ClassNotFoundException {
        Set<String> found = new HashSet<>();
        Path root = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules/java.base/java/time");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = root.getParent().getParent().relativize(file).toString().replace('\\', '/').replace('/', '.');
                name = name.substring(0, name.length() - ".class".length());
                Class<?> type = Class.forName(name, false, ClassLoader.getPlatformClassLoader());
                if (!Modifier.isPublic(type.getModifiers())) {
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
                            && method.getName().equals("now") && !List.of(method.getParameterTypes()).contains(java.time.Clock.class)) {
                        found.add(internalName(type) + ".now"
                                + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
                    }
                }
            }
        }
        assertTrue(found.size() >= 20, "the search found too few now() methods: " + found);
        Set<String> missing = new HashSet<>(found);
        missing.removeAll(MEMBERS);
        assertEquals(Set.of(), missing, "these ask the system clock and nothing redirects them");
    }

    @Test
    void everyStaticMethodOfClockThatMakesASystemClockIsRedirected() {
        List<String> missing = new ArrayList<>();
        for (Method method : java.time.Clock.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && java.time.Clock.class.isAssignableFrom(method.getReturnType())
                    && !method.getName().equals("fixed") && !method.getName().equals("offset") && !method.getName().equals("tick")) {
                String member = "java/time/Clock." + method.getName()
                        + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
                if (!MEMBERS.contains(member)) {
                    missing.add(member);
                }
            }
        }
        assertEquals(List.of(), missing);
    }
}
