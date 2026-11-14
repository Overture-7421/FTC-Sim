package dev.ftcsim.scenarios.hooks;

import java.util.List;

/**
 * Where the hook fixtures report to. The test hands over a list through the JVM's system properties, which both
 * the test and the robot world see, so the events can be read after the robot world has been closed.
 */
final class Events {
    static final String PROPERTY = "ftcsim.test.hookEvents";

    private Events() {}

    @SuppressWarnings("unchecked")
    static void record(String event) {
        Object sink = System.getProperties().get(PROPERTY);
        if (sink instanceof List<?> events) {
            ((List<Object>) events).add(event);
        }
    }

    static boolean enabled(String flag) {
        return System.getProperty(flag) != null;
    }
}
