package dev.ftcsim.runtime.fixture.hidden;

/** Package-private class in another package, matching Kotlin's {@code private object CALLSITE} with {@code @JvmStatic}. */
final class HiddenCallsite {
    int packageField = 10;

    public static String ping(String argument) {
        return "pong:" + argument;
    }

    public String instancePing(String argument) {
        return "instance:" + argument;
    }

    static String hiddenStatic() {
        return "secret";
    }
}
