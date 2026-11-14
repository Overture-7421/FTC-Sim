package dev.ftcsim.hardware.camera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small strict JSON reader for tests: objects become maps, arrays lists, numbers doubles. */
final class Json {

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static Map<String, Object> object(String text) {
        Object value = new Json(text).document();
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) map;
            return object;
        }
        throw new IllegalArgumentException("not an object: " + text);
    }

    private Object document() {
        Object value = value();
        skip();
        if (position != text.length()) {
            throw error("unexpected text after the document");
        }
        return value;
    }

    private Object value() {
        skip();
        char c = text.charAt(position);
        if (c == '{') {
            return readObject();
        }
        if (c == '[') {
            return readArray();
        }
        if (c == '"') {
            return readString();
        }
        if (text.startsWith("true", position)) {
            position += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", position)) {
            position += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", position)) {
            position += 4;
            return null;
        }
        return readNumber();
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        position++;
        skip();
        if (text.charAt(position) == '}') {
            position++;
            return object;
        }
        for (;;) {
            skip();
            String key = readString();
            skip();
            expect(':');
            if (object.put(key, value()) != null) {
                throw error("duplicate key " + key);
            }
            skip();
            if (text.charAt(position) == ',') {
                position++;
            } else {
                expect('}');
                return object;
            }
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        position++;
        skip();
        if (text.charAt(position) == ']') {
            position++;
            return array;
        }
        for (;;) {
            array.add(value());
            skip();
            if (text.charAt(position) == ',') {
                position++;
            } else {
                expect(']');
                return array;
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder string = new StringBuilder();
        while (text.charAt(position) != '"') {
            char c = text.charAt(position++);
            if (c == '\\') {
                char escaped = text.charAt(position++);
                string.append(escaped == 'n' ? '\n' : escaped);
            } else {
                string.append(c);
            }
        }
        position++;
        return string.toString();
    }

    private Double readNumber() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        if (start == position) {
            throw error("a value was expected");
        }
        return Double.valueOf(text.substring(start, position));
    }

    private void expect(char c) {
        if (text.charAt(position) != c) {
            throw error("'" + c + "' was expected");
        }
        position++;
    }

    private void skip() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private IllegalArgumentException error(String problem) {
        return new IllegalArgumentException(problem + " at " + position + " of " + text);
    }

    @SuppressWarnings("unchecked")
    static double[] numbers(Object array) {
        List<Object> list = (List<Object>) array;
        double[] numbers = new double[list.size()];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = (Double) list.get(i);
        }
        return numbers;
    }
}
