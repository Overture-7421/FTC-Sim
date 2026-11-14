package dev.ftcsim.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** The set of JDK calls the simulator virtualises. One table drives both direct calls and method references. */
final class RewriteRules {
    private static final String SYSTEM = "java/lang/System";
    private static final String RUNTIME = "java/lang/Runtime";
    private static final String THREAD = "java/lang/Thread";
    private static final String TIME_UNIT = "java/util/concurrent/TimeUnit";
    private static final String ANY = RewriteRule.ANY_OWNER;

    static final String HOOKS = "dev/ftcsim/contracts/hooks/Hooks";

    /** A method whose first instruction is preceded by a call to a hook that takes no arguments. */
    record EntryHook(String owner, String name, String descriptor, String hookName) {
        String key() {
            return owner + '.' + name + descriptor;
        }
    }

    /**
     * A constructor that is given one more argument, a {@code long} the hook supplies: {@code Random()} becomes
     * {@code Random(seed)}. It is how a {@code super()} or {@code this()} call, which no factory can replace, gets the
     * simulator's seed or clock; a {@code new} of the type is replaced by the factory of a {@link RewriteRule}.
     *
     * @param descriptor       the constructor as the program calls it
     * @param seededDescriptor the constructor that is called instead, which takes the same arguments and then the long
     * @param hookOwner        where the supplier of the long is
     * @param hookName         the supplier, a static method that takes nothing and returns a long
     */
    record SeededConstructor(String owner, String descriptor, String seededDescriptor, String hookOwner, String hookName) {
        String key() {
            return owner + '.' + RewriteRule.CONSTRUCTOR + descriptor;
        }
    }

    /**
     * Static methods that call sites name through a subclass, which is how the JDK's own code and robot code alike
     * write {@code GregorianCalendar.getInstance()}.
     */
    private static final Map<String, String> STATIC_SUBTYPES = Map.of("java/util/GregorianCalendar", "java/util/Calendar");

    private final Map<String, RewriteRule> byKey = new HashMap<>();
    private final Map<String, EntryHook> entryHooks = new HashMap<>();
    private final Map<String, SeededConstructor> seededConstructors = new HashMap<>();
    private final Set<String> constructed = new HashSet<>();

    RewriteRules(List<RewriteRule> rules) {
        this(rules, List.of());
    }

    RewriteRules(List<RewriteRule> rules, List<EntryHook> entryHooks) {
        this(rules, entryHooks, List.of());
    }

    RewriteRules(List<RewriteRule> rules, List<EntryHook> entryHooks, List<SeededConstructor> seededConstructors) {
        for (RewriteRule rule : rules) {
            byKey.put(rule.key(), rule);
            if (rule.isConstructor()) {
                constructed.add(rule.owner());
            }
        }
        for (EntryHook hook : entryHooks) {
            this.entryHooks.put(hook.key(), hook);
        }
        for (SeededConstructor seeded : seededConstructors) {
            this.seededConstructors.put(seeded.key(), seeded);
        }
    }

    static RewriteRules standard() {
        return new RewriteRules(
                Stream.of(clockAndMonitorRules(), NetworkRules.all(), TimeRules.all(), RandomRules.all()).flatMap(List::stream).toList(),
                // Where the SDK's pool threads begin; the thread that runs OpMode code is one of them.
                List.of(new EntryHook("com/qualcomm/robotcore/util/ThreadPool$ThreadFactoryImpl$1", "run", "()V", "threadStarted")),
                RandomRules.seeded());
    }

    private static List<RewriteRule> clockAndMonitorRules() {
        return List.of(
                new RewriteRule(SYSTEM, "nanoTime", "()J", "nanoTime", "()J", false),
                new RewriteRule(SYSTEM, "currentTimeMillis", "()J", "currentTimeMillis", "()J", false),
                new RewriteRule(THREAD, "sleep", "(J)V", "sleep", "(J)V", false),
                new RewriteRule(THREAD, "sleep", "(JI)V", "sleep", "(JI)V", false),
                new RewriteRule(THREAD, "yield", "()V", "yieldThread", "()V", false),
                new RewriteRule(TIME_UNIT, "sleep", "(J)V", "sleep", "(Ljava/util/concurrent/TimeUnit;J)V", true),
                new RewriteRule(SYSTEM, "loadLibrary", "(Ljava/lang/String;)V", "loadLibrary", "(Ljava/lang/String;)V", false),
                new RewriteRule(SYSTEM, "load", "(Ljava/lang/String;)V", "load", "(Ljava/lang/String;)V", false),
                new RewriteRule(SYSTEM, "exit", "(I)V", "exit", "(I)V", false),
                new RewriteRule(RUNTIME, "exit", "(I)V", "exit", "(Ljava/lang/Runtime;I)V", true),
                new RewriteRule(RUNTIME, "halt", "(I)V", "halt", "(Ljava/lang/Runtime;I)V", true),
                new RewriteRule(RUNTIME, "addShutdownHook", "(Ljava/lang/Thread;)V", "addShutdownHook", "(Ljava/lang/Runtime;Ljava/lang/Thread;)V", true),
                new RewriteRule(RUNTIME, "removeShutdownHook", "(Ljava/lang/Thread;)Z", "removeShutdownHook", "(Ljava/lang/Runtime;Ljava/lang/Thread;)Z", true),
                new RewriteRule(ANY, "wait", "()V", "wait", "(Ljava/lang/Object;)V", true),
                new RewriteRule(ANY, "wait", "(J)V", "wait", "(Ljava/lang/Object;J)V", true),
                new RewriteRule(ANY, "wait", "(JI)V", "wait", "(Ljava/lang/Object;JI)V", true),
                new RewriteRule(ANY, "notify", "()V", "notify", "(Ljava/lang/Object;)V", true),
                new RewriteRule(ANY, "notifyAll", "()V", "notifyAll", "(Ljava/lang/Object;)V", true));
    }

    Optional<EntryHook> entryHook(String owner, String name, String descriptor) {
        return Optional.ofNullable(entryHooks.get(owner + '.' + name + descriptor));
    }

    /** The constructor that stands in for a {@code super()} or {@code this()} call of this constructor, if there is one. */
    Optional<SeededConstructor> seeded(String owner, String descriptor) {
        return Optional.ofNullable(seededConstructors.get(owner + '.' + RewriteRule.CONSTRUCTOR + descriptor));
    }

    /** Whether some constructor of the type is redirected, so creating one is a call site worth following. */
    boolean constructs(String type) {
        return constructed.contains(type);
    }

    /**
     * Finds the rule for a call. For instance calls a rule also matches a subtype the receiver was declared as
     * (see {@link NetworkRules#SUBTYPES}), and rules for methods that cannot be overridden match any declared type.
     */
    Optional<RewriteRule> find(String owner, String name, String descriptor, boolean instanceCall) {
        RewriteRule exact = byKey.get(RewriteRule.key(owner, name, descriptor));
        if (exact != null) {
            return Optional.of(exact);
        }
        if (!instanceCall) {
            String supertype = STATIC_SUBTYPES.get(owner);
            return supertype == null ? Optional.empty() : Optional.ofNullable(byKey.get(RewriteRule.key(supertype, name, descriptor)));
        }
        String supertype = NetworkRules.SUBTYPES.get(owner);
        if (supertype != null && !RewriteRule.CONSTRUCTOR.equals(name)) {
            RewriteRule inherited = byKey.get(RewriteRule.key(supertype, name, descriptor));
            if (inherited != null) {
                return Optional.of(inherited);
            }
        }
        return Optional.ofNullable(byKey.get(RewriteRule.key(ANY, name, descriptor)));
    }
}
