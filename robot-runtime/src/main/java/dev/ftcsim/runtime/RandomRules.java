package dev.ftcsim.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * The JDK's ways of getting numbers nobody can predict, redirected to {@code RandomHooks} so that the numbers follow from
 * the host's seed: a {@code Random} or {@code SplittableRandom} made without a seed, {@code Math.random}, {@code
 * UUID.randomUUID}, every way into a {@code SecureRandom}, {@code Collections.shuffle} without a generator and {@code
 * ThreadLocalRandom.current}. A generator the program seeds itself is its own business and is not here.
 *
 * <p>Each row names the JDK member and the replacement, whose descriptor follows from the member's, a constructor
 * becoming a factory that returns the new object.
 */
final class RandomRules {
    static final String HOOKS = "dev/ftcsim/contracts/hooks/RandomHooks";

    private static final String SECURE_RANDOM = "java/security/SecureRandom";
    private static final String SECURE_RANDOM_TYPE = "Ljava/security/SecureRandom;";
    private static final String PARAMETERS = "Ljava/security/SecureRandomParameters;";

    private RandomRules() {}

    static List<RewriteRule> all() {
        List<RewriteRule> rules = new ArrayList<>();

        factory(rules, "java/util/Random", "newRandom", "()V");
        factory(rules, "java/util/SplittableRandom", "newSplittableRandom", "()V");
        factory(rules, SECURE_RANDOM, "newSecureRandom", "()V", "([B)V");

        statics(rules, "java/lang/Math", "random", "mathRandom", "D", "()");
        statics(rules, "java/lang/StrictMath", "random", "mathRandom", "D", "()");
        statics(rules, "java/util/UUID", "randomUUID", "randomUUID", "Ljava/util/UUID;", "()");
        statics(rules, "java/util/Collections", "shuffle", "shuffle", "V", "(Ljava/util/List;)");
        statics(rules, "java/util/concurrent/ThreadLocalRandom", "current", "threadLocalRandom",
                "Ljava/util/concurrent/ThreadLocalRandom;", "()");

        statics(rules, SECURE_RANDOM, "getInstance", "getInstance", SECURE_RANDOM_TYPE,
                "(Ljava/lang/String;)",
                "(Ljava/lang/String;Ljava/lang/String;)",
                "(Ljava/lang/String;Ljava/security/Provider;)",
                "(Ljava/lang/String;" + PARAMETERS + ")",
                "(Ljava/lang/String;" + PARAMETERS + "Ljava/lang/String;)",
                "(Ljava/lang/String;" + PARAMETERS + "Ljava/security/Provider;)");
        statics(rules, SECURE_RANDOM, "getInstanceStrong", "getInstanceStrong", SECURE_RANDOM_TYPE, "()");
        statics(rules, SECURE_RANDOM, "getSeed", "getSeed", "[B", "(I)");
        return List.copyOf(rules);
    }

    /**
     * What a subclass of a generator does first, {@code super()}, which no factory can stand for: the constructor that
     * takes a seed, given one.
     */
    static List<RewriteRules.SeededConstructor> seeded() {
        return List.of(
                new RewriteRules.SeededConstructor("java/util/Random", "()V", "(J)V", RewriteRules.HOOKS, "randomSeed"),
                new RewriteRules.SeededConstructor("java/util/Date", "()V", "(J)V", RewriteRules.HOOKS, "currentTimeMillis"));
    }

    private static void factory(List<RewriteRule> rules, String owner, String hook, String... descriptors) {
        for (String descriptor : descriptors) {
            String returning = descriptor.substring(0, descriptor.length() - 1) + 'L' + owner + ';';
            rules.add(new RewriteRule(owner, RewriteRule.CONSTRUCTOR, descriptor, HOOKS, hook, returning, false));
        }
    }

    private static void statics(
            List<RewriteRule> rules, String owner, String name, String hook, String returning, String... argumentLists) {
        for (String arguments : argumentLists) {
            rules.add(new RewriteRule(owner, name, arguments + returning, HOOKS, hook, arguments + returning, false));
        }
    }
}
