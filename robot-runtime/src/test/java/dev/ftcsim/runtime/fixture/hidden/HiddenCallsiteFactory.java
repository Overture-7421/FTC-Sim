package dev.ftcsim.runtime.fixture.hidden;

/** Exposes {@link HiddenCallsite} to probes in another package without making the class itself public. */
public final class HiddenCallsiteFactory {
    private HiddenCallsiteFactory() {}

    public static Class<?> hiddenClass() {
        return HiddenCallsite.class;
    }

    public static Object hiddenInstance() {
        return new HiddenCallsite();
    }
}
