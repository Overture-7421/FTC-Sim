package android.os;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Android's system property table, which the FTC SDK reads by reflection to learn what kind of device it runs
 * on. The table is the simulator's statement of what controller it pretends to be; anything it does not
 * contain reads as unset, exactly as on a real device.
 */
public final class SystemProperties {
    private static final Map<String, String> PROPERTIES = new ConcurrentHashMap<>();

    private SystemProperties() {}

    /** Replaces the whole table; the host calls this once while it boots. */
    public static void configure(Map<String, String> properties) {
        PROPERTIES.clear();
        PROPERTIES.putAll(properties);
    }

    public static String get(String key, String def) {
        return PROPERTIES.getOrDefault(key, def == null ? "" : def);
    }

    public static int getInt(String key, int def) {
        String value = PROPERTIES.get(key);
        try {
            return value == null ? def : Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return def;
        }
    }

    public static long getLong(String key, long def) {
        String value = PROPERTIES.get(key);
        try {
            return value == null ? def : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return def;
        }
    }

    /** Android accepts exactly these spellings and treats everything else as the default. */
    public static boolean getBoolean(String key, boolean def) {
        String value = PROPERTIES.get(key);
        if (value == null) {
            return def;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "y", "yes", "on", "true" -> true;
            case "0", "n", "no", "off", "false" -> false;
            default -> def;
        };
    }

    public static void set(String key, String value) {
        PROPERTIES.put(key, value == null ? "" : value);
    }
}
