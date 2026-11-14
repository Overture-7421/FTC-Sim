package dev.ftcsim.hardware.camera;

import java.util.ArrayList;
import java.util.List;

/** Reads the one shape of JSON that robot code sends a camera: an array of numbers such as {@code [45,0,0,0,0,0]}. */
final class JsonNumbers {

    private JsonNumbers() {}

    /** The numbers of the array, or null if the text is not a JSON array of numbers. */
    static double[] parseArray(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            return null;
        }
        String inside = trimmed.substring(1, trimmed.length() - 1).strip();
        if (inside.isEmpty()) {
            return new double[0];
        }
        List<Double> values = new ArrayList<>();
        for (String part : inside.split(",", -1)) {
            String number = part.strip();
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                return null;
            }
            double value = Double.parseDouble(number);
            if (!Double.isFinite(value)) {
                return null;
            }
            values.add(value);
        }
        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }
}
