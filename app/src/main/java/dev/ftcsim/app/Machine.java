package dev.ftcsim.app;

import java.nio.file.Path;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the application asks of the computer it runs on: its environment variables, from which it finds the folders
 * that Windows keeps programs and user data in. A test describes a computer with a folder and a few variables.
 *
 * @param environment the value of an environment variable by name, or null when it is not set
 */
public record Machine(UnaryOperator<String> environment) {
    private static final Pattern VARIABLE = Pattern.compile("%([^%]+)%");

    /** The computer this program runs on. */
    public static Machine real() {
        return new Machine(System::getenv);
    }

    /** The value of an environment variable, without surrounding quotes, or null when it is not set or is blank. */
    public String variable(String name) {
        String value = environment.apply(name);
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.isEmpty() ? null : text;
    }

    /** {@code %LOCALAPPDATA%}, or {@code AppData\Local} in the user's profile when Windows did not say. */
    public Path localAppData() {
        String local = variable("LOCALAPPDATA");
        return local == null ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(local);
    }

    /** {@code text} with every {@code %NAME%} replaced by the value of the variable, or null when one of them is not set. */
    String expand(String text) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String value = variable(matcher.group(1));
            if (value == null) {
                return null;
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }
}
