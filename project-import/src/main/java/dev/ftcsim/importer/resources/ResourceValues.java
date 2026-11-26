package dev.ftcsim.importer.resources;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How Android reads the text of a value resource, and how a value is written to the resource table. */
final class ResourceValues {
    private static final Pattern STRING_REFERENCE = Pattern.compile("@string/([A-Za-z0-9_.]+)");
    private static final Pattern DIMEN_REFERENCE = Pattern.compile("@dimen/([A-Za-z0-9_.]+)");

    private ResourceValues() {}

    /** The text of a string element as {@code getString} returns it: whitespace collapses unless quoted, and backslash escapes apply. */
    static String resolveString(String raw) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                i++;
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                char escaped = raw.charAt(i);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        Integer unit = i + 4 < raw.length() ? hex(raw.substring(i + 1, i + 5)) : null;
                        if (unit != null) {
                            out.append((char) unit.intValue());
                            i += 4;
                        } else {
                            out.append(escaped);
                        }
                    }
                    default -> out.append(escaped);
                }
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (Character.isWhitespace(c) || Character.isSpaceChar(c))) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A value that is nothing but {@code @string/other} stands for the text of that string. */
    static String resolveReference(String value, Map<String, String> strings) {
        Matcher reference = STRING_REFERENCE.matcher(value);
        if (!reference.matches()) {
            return value;
        }
        String target = strings.get(ValuesXml.symbolName(reference.group(1)));
        return target == null ? value : resolveString(target);
    }

    /** A value that is nothing but {@code @dimen/other} stands for that dimension's value. */
    static String resolveDimenReference(String value, Map<String, String> dimensions) {
        String current = value;
        for (int hops = 0; hops < 8; hops++) {
            Matcher reference = DIMEN_REFERENCE.matcher(current);
            if (!reference.matches()) {
                return current;
            }
            String target = dimensions.get(ValuesXml.symbolName(reference.group(1)));
            if (target == null) {
                return current;
            }
            current = target.trim();
        }
        return current;
    }

    /** The table is one resource per line, so backslash, newline, tab and carriage return are escaped. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
    }

    private static Integer hex(String digits) {
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException notHex) {
            return null;
        }
    }
}
