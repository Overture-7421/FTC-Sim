package dev.ftcsim.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The JDK's ways of asking what time it is, redirected to {@code TimeHooks} so that a robot program reads the simulated
 * clock: {@code new Date()}, {@code Calendar.getInstance}, {@code new GregorianCalendar}, {@code Clock.system*}, and
 * {@code now()} of every class of {@code java.time}. {@code System.currentTimeMillis} and {@code nanoTime} are in
 * {@link RewriteRules}. Each row names the JDK member; the replacement has the name the table gives and its descriptor
 * follows from the member's, a constructor becoming a factory that returns the new object.
 *
 * <p>The members that take a clock, such as {@code LocalDate.now(Clock)}, are left alone: the program chose that clock, and
 * the ones it can get from the JDK are replaced.
 */
final class TimeRules {
    static final String HOOKS = "dev/ftcsim/contracts/hooks/TimeHooks";

    private static final String ZONE = "Ljava/time/ZoneId;";

    /** The classes whose static {@code now()} and {@code now(ZoneId)} read the system clock, with their replacements. */
    static final Map<String, String> NOW = Map.ofEntries(
            Map.entry("java/time/LocalDate", "localDateNow"),
            Map.entry("java/time/LocalTime", "localTimeNow"),
            Map.entry("java/time/LocalDateTime", "localDateTimeNow"),
            Map.entry("java/time/ZonedDateTime", "zonedDateTimeNow"),
            Map.entry("java/time/OffsetDateTime", "offsetDateTimeNow"),
            Map.entry("java/time/OffsetTime", "offsetTimeNow"),
            Map.entry("java/time/Year", "yearNow"),
            Map.entry("java/time/YearMonth", "yearMonthNow"),
            Map.entry("java/time/MonthDay", "monthDayNow"),
            Map.entry("java/time/chrono/HijrahDate", "hijrahDateNow"),
            Map.entry("java/time/chrono/JapaneseDate", "japaneseDateNow"),
            Map.entry("java/time/chrono/MinguoDate", "minguoDateNow"),
            Map.entry("java/time/chrono/ThaiBuddhistDate", "thaiBuddhistDateNow"));

    private TimeRules() {}

    /**
     * What a subclass of {@code Date} does first, {@code super()}, which no factory can stand for: the constructor that
     * takes the time in milliseconds, given the simulated clock's.
     */
    static List<RewriteRules.SeededConstructor> seeded() {
        return List.of(new RewriteRules.SeededConstructor("java/util/Date", "()V", "(J)V", RewriteRules.HOOKS, "currentTimeMillis"));
    }

    static List<RewriteRule> all() {
        List<RewriteRule> rules = new ArrayList<>();

        factory(rules, "java/util/Date", "newDate", "()V");
        factory(rules, "java/util/GregorianCalendar", "newGregorianCalendar",
                "()V", "(Ljava/util/TimeZone;)V", "(Ljava/util/Locale;)V", "(Ljava/util/TimeZone;Ljava/util/Locale;)V");
        statics(rules, "java/util/Calendar", "getInstance", "getInstance", "Ljava/util/Calendar;",
                "()", "(Ljava/util/TimeZone;)", "(Ljava/util/Locale;)", "(Ljava/util/TimeZone;Ljava/util/Locale;)");

        statics(rules, "java/time/Clock", "systemUTC", "systemUtc", "Ljava/time/Clock;", "()");
        statics(rules, "java/time/Clock", "systemDefaultZone", "systemDefaultZone", "Ljava/time/Clock;", "()");
        statics(rules, "java/time/Clock", "system", "system", "Ljava/time/Clock;", "(" + ZONE + ")");
        statics(rules, "java/time/Clock", "tickMillis", "tickMillis", "Ljava/time/Clock;", "(" + ZONE + ")");
        statics(rules, "java/time/Clock", "tickSeconds", "tickSeconds", "Ljava/time/Clock;", "(" + ZONE + ")");
        statics(rules, "java/time/Clock", "tickMinutes", "tickMinutes", "Ljava/time/Clock;", "(" + ZONE + ")");

        statics(rules, "java/time/Instant", "now", "instantNow", "Ljava/time/Instant;", "()");
        NOW.forEach((type, hook) -> statics(rules, type, "now", hook, "L" + type + ";", "()", "(" + ZONE + ")"));
        return List.copyOf(rules);
    }

    /** {@code new T(arguments)} becomes {@code TimeHooks.hook(arguments)}. */
    private static void factory(List<RewriteRule> rules, String owner, String hook, String... descriptors) {
        for (String descriptor : descriptors) {
            String returning = descriptor.substring(0, descriptor.length() - 1) + 'L' + owner + ';';
            rules.add(new RewriteRule(owner, RewriteRule.CONSTRUCTOR, descriptor, HOOKS, hook, returning, false));
        }
    }

    /** A static method of the JDK and the hook of the same arguments that returns the same type. */
    private static void statics(
            List<RewriteRule> rules, String owner, String name, String hook, String returning, String... argumentLists) {
        for (String arguments : argumentLists) {
            rules.add(new RewriteRule(owner, name, arguments + returning, HOOKS, hook, arguments + returning, false));
        }
    }
}
