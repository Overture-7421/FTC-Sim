package android.graphics;

import java.util.Locale;
import java.util.Map;

/**
 * Colors as packed ARGB integers, with the conversions robot code and the SDK use. Written from the documented
 * behaviour of the Android class of the same name.
 */
public final class Color {
    public static final int BLACK = 0xFF000000;
    public static final int DKGRAY = 0xFF444444;
    public static final int GRAY = 0xFF888888;
    public static final int LTGRAY = 0xFFCCCCCC;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int RED = 0xFFFF0000;
    public static final int GREEN = 0xFF00FF00;
    public static final int BLUE = 0xFF0000FF;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int CYAN = 0xFF00FFFF;
    public static final int MAGENTA = 0xFFFF00FF;
    public static final int TRANSPARENT = 0;

    private static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("black", BLACK),
            Map.entry("darkgray", DKGRAY),
            Map.entry("gray", GRAY),
            Map.entry("lightgray", LTGRAY),
            Map.entry("white", WHITE),
            Map.entry("red", RED),
            Map.entry("green", GREEN),
            Map.entry("blue", BLUE),
            Map.entry("yellow", YELLOW),
            Map.entry("cyan", CYAN),
            Map.entry("magenta", MAGENTA),
            Map.entry("aqua", 0xFF00FFFF),
            Map.entry("fuchsia", 0xFFFF00FF),
            Map.entry("darkgrey", DKGRAY),
            Map.entry("grey", GRAY),
            Map.entry("lightgrey", LTGRAY),
            Map.entry("lime", 0xFF00FF00),
            Map.entry("maroon", 0xFF800000),
            Map.entry("navy", 0xFF000080),
            Map.entry("olive", 0xFF808000),
            Map.entry("purple", 0xFF800080),
            Map.entry("silver", 0xFFC0C0C0),
            Map.entry("teal", 0xFF008080));

    private Color() {
    }

    public static int alpha(int color) {
        return color >>> 24;
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int rgb(int red, int green, int blue) {
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /** The relative luminance of an sRGB color, 0 for black to 1 for white. */
    public static float luminance(int color) {
        return (0.2126f * linear(red(color)) + 0.7152f * linear(green(color)) + 0.0722f * linear(blue(color)));
    }

    private static float linear(int channel) {
        double value = channel / 255.0;
        return (float) (value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4));
    }

    /** Parses "#RRGGBB", "#AARRGGBB" or one of the color names Android knows. */
    public static int parseColor(String text) {
        if (text.startsWith("#")) {
            long value;
            try {
                value = Long.parseLong(text.substring(1), 16);
            } catch (NumberFormatException notHex) {
                throw new IllegalArgumentException("Unknown color: " + text);
            }
            if (text.length() == 7) {
                return (int) (value | 0xFF000000L);
            }
            if (text.length() == 9) {
                return (int) value;
            }
            throw new IllegalArgumentException("Unknown color: " + text);
        }
        Integer named = NAMES.get(text.toLowerCase(Locale.ROOT));
        if (named == null) {
            throw new IllegalArgumentException("Unknown color: " + text);
        }
        return named;
    }

    /**
     * Converts to hue (degrees, 0 up to 360), saturation and value (0 to 1).
     *
     * @param hsv receives the three components
     */
    public static void colorToHSV(int color, float[] hsv) {
        if (hsv.length < 3) {
            throw new IllegalArgumentException("hsv must have room for 3 values");
        }
        float red = red(color) / 255f;
        float green = green(color) / 255f;
        float blue = blue(color) / 255f;
        float max = Math.max(red, Math.max(green, blue));
        float delta = max - Math.min(red, Math.min(green, blue));
        float hue;
        if (delta == 0f) {
            hue = 0f;
        } else if (max == red) {
            hue = 60f * (((green - blue) / delta) % 6f);
        } else if (max == green) {
            hue = 60f * ((blue - red) / delta + 2f);
        } else {
            hue = 60f * ((red - green) / delta + 4f);
        }
        hsv[0] = hue < 0f ? hue + 360f : hue;
        hsv[1] = max == 0f ? 0f : delta / max;
        hsv[2] = max;
    }

    public static int HSVToColor(float[] hsv) {
        return HSVToColor(255, hsv);
    }

    public static int HSVToColor(int alpha, float[] hsv) {
        if (hsv.length < 3) {
            throw new IllegalArgumentException("hsv must have 3 values");
        }
        float hue = ((hsv[0] % 360f) + 360f) % 360f;
        float saturation = Math.max(0f, Math.min(1f, hsv[1]));
        float value = Math.max(0f, Math.min(1f, hsv[2]));
        float chroma = value * saturation;
        float x = chroma * (1f - Math.abs((hue / 60f) % 2f - 1f));
        float m = value - chroma;
        float red;
        float green;
        float blue;
        switch ((int) (hue / 60f)) {
            case 0 -> { red = chroma; green = x; blue = 0f; }
            case 1 -> { red = x; green = chroma; blue = 0f; }
            case 2 -> { red = 0f; green = chroma; blue = x; }
            case 3 -> { red = 0f; green = x; blue = chroma; }
            case 4 -> { red = x; green = 0f; blue = chroma; }
            default -> { red = chroma; green = 0f; blue = x; }
        }
        return argb(alpha, Math.round((red + m) * 255f), Math.round((green + m) * 255f), Math.round((blue + m) * 255f));
    }
}
