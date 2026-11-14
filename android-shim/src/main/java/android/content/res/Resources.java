package android.content.res;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Access to the application's resources by id. On a device the ids come from the linked resource table of the
 * APK; here they come from every {@code ftcsim-resources.tsv} on the robot class path, which are written together
 * with the {@code R} classes that carry the same ids (tab separated: type, id, name, value with {@code \\},
 * {@code \n}, {@code \t} and {@code \r} escaped; lines that start with {@code #} are comments). Strings, integers,
 * booleans, colors and dimensions are available. A dimension is read for a screen of 160 dots per inch, on which
 * one density-independent pixel is one pixel.
 */
public class Resources {
    private static final String TABLE = "ftcsim-resources.tsv";
    private static final Pattern DIMENSION = Pattern.compile("\\s*(-?[0-9]*\\.?[0-9]+)\\s*(px|dp|dip|sp|pt|in|mm)\\s*");
    private static final float DOTS_PER_INCH = 160f;

    /** Thrown when a resource id has no value of the requested type. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    private final Map<Integer, String> strings = new HashMap<>();
    private final Map<Integer, String> integers = new HashMap<>();
    private final Map<Integer, String> booleans = new HashMap<>();
    private final Map<Integer, String> colors = new HashMap<>();
    private final Map<Integer, String> dimensions = new HashMap<>();
    /** The id of each resource by "type/name", for lookups by name. */
    private final Map<String, Integer> identifiers = new HashMap<>();

    public Resources() {
        try {
            Enumeration<URL> tables = Resources.class.getClassLoader().getResources(TABLE);
            if (!tables.hasMoreElements()) {
                throw new NotFoundException(TABLE + " is not on the robot class path, so no Android resource can be read");
            }
            while (tables.hasMoreElements()) {
                // Without the cache closing the stream closes the jar, so no table keeps its file locked.
                URLConnection connection = tables.nextElement().openConnection();
                connection.setUseCaches(false);
                try (InputStream stream = connection.getInputStream()) {
                    read(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            throw new NotFoundException("The Android resource table cannot be read: " + e);
        }
    }

    private void read(BufferedReader table) throws IOException {
        for (String line = table.readLine(); line != null; line = table.readLine()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t", 4);
            Map<Integer, String> values = switch (columns[0]) {
                case "string" -> strings;
                case "integer" -> integers;
                case "bool" -> booleans;
                case "color" -> colors;
                case "dimen" -> dimensions;
                default -> null;
            };
            if (values != null) {
                int id = Integer.decode(columns[1]);
                values.put(id, unescape(columns[3]));
                identifiers.put(columns[0] + "/" + columns[2], id);
            }
        }
    }

    /**
     * The id of a resource named {@code name}, which may also be written {@code type/name} or
     * {@code package:type/name}, of the given type; 0 when there is no such resource. The package is not
     * consulted: resource names are unique across the libraries of one app.
     */
    public int getIdentifier(String name, String defType, String defPackage) {
        String entry = name.substring(name.indexOf(':') + 1);
        String type = defType;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            type = entry.substring(0, slash);
            entry = entry.substring(slash + 1);
        }
        return type == null ? 0 : identifiers.getOrDefault(type + "/" + entry.replace('.', '_'), 0);
    }

    public String getString(int id) {
        return lookup(strings, id, "String");
    }

    public String getString(int id, Object... formatArgs) {
        return String.format(Locale.getDefault(), getString(id), formatArgs);
    }

    public int getInteger(int id) {
        return Integer.decode(lookup(integers, id, "Integer"));
    }

    public boolean getBoolean(int id) {
        return Boolean.parseBoolean(lookup(booleans, id, "Boolean"));
    }

    public int getColor(int id) {
        String color = lookup(colors, id, "Color").substring(1);
        long value = Long.parseLong(color.length() == 3 || color.length() == 4 ? expandShortColor(color) : color, 16);
        return (int) (color.length() == 3 || color.length() == 6 ? 0xff000000L | value : value);
    }

    /** A dimension in pixels. */
    public float getDimension(int id) {
        String text = lookup(dimensions, id, "Dimension");
        Matcher matcher = DIMENSION.matcher(text);
        if (!matcher.matches()) {
            throw new NotFoundException("Dimension resource ID #0x" + Integer.toHexString(id) + " is not a dimension: " + text);
        }
        float number = Float.parseFloat(matcher.group(1));
        return switch (matcher.group(2)) {
            case "pt" -> number * DOTS_PER_INCH / 72f;
            case "in" -> number * DOTS_PER_INCH;
            case "mm" -> number * DOTS_PER_INCH / 25.4f;
            default -> number;
        };
    }

    /** A dimension in pixels with its fraction cut off. */
    public int getDimensionPixelOffset(int id) {
        return (int) getDimension(id);
    }

    /** A dimension in whole pixels, rounded, and at least one pixel unless it is zero. */
    public int getDimensionPixelSize(int id) {
        float dimension = getDimension(id);
        int rounded = Math.round(dimension);
        if (rounded != 0) {
            return rounded;
        }
        return dimension == 0 ? 0 : dimension > 0 ? 1 : -1;
    }

    private static String lookup(Map<Integer, String> values, int id, String kind) {
        String value = values.get(id);
        if (value == null) {
            throw new NotFoundException(kind + " resource ID #0x" + Integer.toHexString(id));
        }
        return value;
    }

    private static String expandShortColor(String color) {
        StringBuilder expanded = new StringBuilder();
        for (char digit : color.toCharArray()) {
            expanded.append(digit).append(digit);
        }
        return expanded.toString();
    }

    private static String unescape(String value) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) {
                text.append(c);
                continue;
            }
            char escaped = value.charAt(++i);
            text.append(
                    switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> escaped;
                    });
        }
        return text.toString();
    }
}
