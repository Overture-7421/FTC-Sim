package android.graphics;

/**
 * A font. The simulated controller draws no text, so a typeface is only a named handle that views can be given
 * and report back.
 */
public final class Typeface {
    public static final Typeface DEFAULT = new Typeface("DEFAULT");
    public static final Typeface DEFAULT_BOLD = new Typeface("DEFAULT_BOLD");
    public static final Typeface SANS_SERIF = new Typeface("SANS_SERIF");
    public static final Typeface SERIF = new Typeface("SERIF");
    public static final Typeface MONOSPACE = new Typeface("MONOSPACE");

    private final String name;

    private Typeface(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "Typeface[" + name + "]";
    }
}
