package dev.ftcsim.hardware.device;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The settings of one device as the robot's design gives them: names mapped to numbers or text, immutable. Every
 * getter names the device and the setting when it fails, and a setting that the device model never asked for is
 * rejected once the model is built, so a misspelling in a setting's name fails loudly instead of changing nothing.
 */
public final class DeviceConfig {

    private final String typeId;
    private final String deviceName;
    private final Map<String, Object> values;
    private final Set<String> asked = new TreeSet<>();

    private DeviceConfig(String typeId, String deviceName, Map<String, Object> values) {
        this.typeId = typeId;
        this.deviceName = deviceName;
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * @param typeId     the type id of the device, for messages
     * @param deviceName the name robot code knows the device by, for messages
     * @param settings   numbers (any {@link Number}) and text; anything else is refused
     */
    public static DeviceConfig of(String typeId, String deviceName, Map<String, ?> settings) {
        DeviceConfig error = new DeviceConfig(typeId, deviceName, Map.of());
        Map<String, Object> copy = new TreeMap<>();
        settings.forEach((key, value) -> {
            if (value instanceof Number number) {
                if (!Double.isFinite(number.doubleValue())) {
                    throw error.rejected(key, "must be a finite number, not " + number);
                }
                copy.put(key, number.doubleValue());
            } else if (value instanceof String text) {
                copy.put(key, text);
            } else {
                throw error.rejected(key, "must be a number or text, not " + describe(value));
            }
        });
        return new DeviceConfig(typeId, deviceName, copy);
    }

    public boolean has(String key) {
        asked.add(key);
        return values.containsKey(key);
    }

    /** Whether any setting is named {@code prefix.something}, to tell an optional group of settings from its absence. */
    public boolean hasGroup(String prefix) {
        return values.keySet().stream().anyMatch(key -> key.startsWith(prefix + "."));
    }

    public double number(String key) {
        return numberOf(key, required(key));
    }

    public double number(String key, double fallback) {
        return has(key) ? number(key) : fallback;
    }

    /** A setting that must be a whole number. */
    public int integer(String key) {
        double value = number(key);
        if (value != Math.rint(value) || Math.abs(value) > Integer.MAX_VALUE) {
            throw rejected(key, "must be a whole number, not " + value);
        }
        return (int) value;
    }

    public int integer(String key, int fallback) {
        return has(key) ? integer(key) : fallback;
    }

    public String text(String key) {
        Object value = required(key);
        if (value instanceof String text) {
            return text;
        }
        throw rejected(key, "must be text, not the number " + value);
    }

    public String text(String key, String fallback) {
        return has(key) ? text(key) : fallback;
    }

    /** The exception to throw for a setting that is present but unusable, worded like the getters' own. */
    public IllegalArgumentException rejected(String key, String problem) {
        return new IllegalArgumentException(where() + ": setting \"" + key + "\" " + problem);
    }

    /** Fails if a setting was given that the device model never read. */
    public void requireAllRead() {
        Set<String> unknown = new TreeSet<>(values.keySet());
        unknown.removeAll(asked);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(where() + ": unknown setting" + (unknown.size() > 1 ? "s " : " ")
                    + String.join(", ", unknown.stream().map(key -> "\"" + key + "\"").toList())
                    + "; this device reads " + String.join(", ", asked));
        }
    }

    private Object required(String key) {
        asked.add(key);
        Object value = values.get(key);
        if (value == null) {
            throw rejected(key, "is missing");
        }
        return value;
    }

    private double numberOf(String key, Object value) {
        if (value instanceof Double number) {
            return number;
        }
        throw rejected(key, "must be a number, not the text \"" + value + "\"");
    }

    private String where() {
        return "device \"" + deviceName + "\" (" + typeId + ")";
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
    }
}
