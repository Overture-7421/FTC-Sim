package dev.ftcsim.ui.input;

import dev.ftcsim.contracts.robot.GamepadState;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which key stands for which control of an FTC gamepad while the keyboard drives one. An immutable value: changing a
 * binding makes a new map. Keys are AWT key codes and 0 means unbound.
 *
 * <p>The defaults put W A S D on the left stick and the arrow keys on the right stick, both held to the unit circle so
 * that a diagonal is not faster than straight ahead; Q and E are the bumpers, Z and C the triggers, I J K L the Y, X, A
 * and B buttons, T F G H the D-pad, X and V the stick buttons, and 1, 2 and 3 Start, Back and Guide.
 */
public final class KeyboardMap {
    private static final PadControl[] CONTROLS = PadControl.values();

    public static final KeyboardMap DEFAULT = defaults();

    private final int[] keys;

    private KeyboardMap(int[] keys) {
        this.keys = keys;
    }

    private static KeyboardMap defaults() {
        int[] keys = new int[CONTROLS.length];
        put(keys, PadControl.LEFT_STICK_UP, KeyEvent.VK_W);
        put(keys, PadControl.LEFT_STICK_DOWN, KeyEvent.VK_S);
        put(keys, PadControl.LEFT_STICK_LEFT, KeyEvent.VK_A);
        put(keys, PadControl.LEFT_STICK_RIGHT, KeyEvent.VK_D);
        put(keys, PadControl.RIGHT_STICK_UP, KeyEvent.VK_UP);
        put(keys, PadControl.RIGHT_STICK_DOWN, KeyEvent.VK_DOWN);
        put(keys, PadControl.RIGHT_STICK_LEFT, KeyEvent.VK_LEFT);
        put(keys, PadControl.RIGHT_STICK_RIGHT, KeyEvent.VK_RIGHT);
        put(keys, PadControl.LEFT_TRIGGER, KeyEvent.VK_Z);
        put(keys, PadControl.RIGHT_TRIGGER, KeyEvent.VK_C);
        put(keys, PadControl.LEFT_BUMPER, KeyEvent.VK_Q);
        put(keys, PadControl.RIGHT_BUMPER, KeyEvent.VK_E);
        put(keys, PadControl.DPAD_UP, KeyEvent.VK_T);
        put(keys, PadControl.DPAD_DOWN, KeyEvent.VK_G);
        put(keys, PadControl.DPAD_LEFT, KeyEvent.VK_F);
        put(keys, PadControl.DPAD_RIGHT, KeyEvent.VK_H);
        put(keys, PadControl.A, KeyEvent.VK_K);
        put(keys, PadControl.B, KeyEvent.VK_L);
        put(keys, PadControl.X, KeyEvent.VK_J);
        put(keys, PadControl.Y, KeyEvent.VK_I);
        put(keys, PadControl.LEFT_STICK_BUTTON, KeyEvent.VK_X);
        put(keys, PadControl.RIGHT_STICK_BUTTON, KeyEvent.VK_V);
        put(keys, PadControl.START, KeyEvent.VK_1);
        put(keys, PadControl.BACK, KeyEvent.VK_2);
        put(keys, PadControl.GUIDE, KeyEvent.VK_3);
        return new KeyboardMap(keys);
    }

    private static void put(int[] keys, PadControl control, int keyCode) {
        keys[control.ordinal()] = keyCode;
    }

    /** The key bound to a control, or 0. */
    public int keyOf(PadControl control) {
        return keys[control.ordinal()];
    }

    /** This map with {@code control} bound to {@code keyCode}, or unbound for 0. */
    public KeyboardMap with(PadControl control, int keyCode) {
        int[] changed = keys.clone();
        changed[control.ordinal()] = Math.max(0, keyCode);
        return new KeyboardMap(changed);
    }

    /** Every binding of this map as a map from control to key, for storing; unbound controls are 0. */
    public Map<PadControl, Integer> bindings() {
        Map<PadControl, Integer> all = new LinkedHashMap<>();
        for (PadControl control : CONTROLS) {
            all.put(control, keys[control.ordinal()]);
        }
        return all;
    }

    /** Whether any control uses this key. */
    public boolean isMapped(int keyCode) {
        if (keyCode == 0) {
            return false;
        }
        for (int key : keys) {
            if (key == keyCode) {
                return true;
            }
        }
        return false;
    }

    /** The controls bound to this key: none, one, or several when the map has a conflict. */
    public List<PadControl> controlsOf(int keyCode) {
        List<PadControl> found = new ArrayList<>(1);
        if (keyCode != 0) {
            for (PadControl control : CONTROLS) {
                if (keys[control.ordinal()] == keyCode) {
                    found.add(control);
                }
            }
        }
        return found;
    }

    /** The keys that more than one control uses, with the controls that share each. */
    public Map<Integer, List<PadControl>> conflicts() {
        Map<Integer, List<PadControl>> shared = new LinkedHashMap<>();
        for (PadControl control : CONTROLS) {
            int key = keys[control.ordinal()];
            if (key != 0 && controlsOf(key).size() > 1) {
                shared.computeIfAbsent(key, ignored -> controlsOf(key));
            }
        }
        return shared;
    }

    /** What a gamepad driven by this map reports while exactly the keys in {@code held} are down. */
    public GamepadState state(Set<Integer> held) {
        if (held.isEmpty()) {
            return GamepadState.NEUTRAL;
        }
        float[] left = stick(held, PadControl.LEFT_STICK_LEFT, PadControl.LEFT_STICK_RIGHT, PadControl.LEFT_STICK_UP, PadControl.LEFT_STICK_DOWN);
        float[] right = stick(held, PadControl.RIGHT_STICK_LEFT, PadControl.RIGHT_STICK_RIGHT, PadControl.RIGHT_STICK_UP, PadControl.RIGHT_STICK_DOWN);
        return new GamepadState(
                left[0], left[1], right[0], right[1],
                down(held, PadControl.LEFT_TRIGGER) ? 1f : 0f, down(held, PadControl.RIGHT_TRIGGER) ? 1f : 0f,
                down(held, PadControl.DPAD_UP), down(held, PadControl.DPAD_DOWN), down(held, PadControl.DPAD_LEFT), down(held, PadControl.DPAD_RIGHT),
                down(held, PadControl.A), down(held, PadControl.B), down(held, PadControl.X), down(held, PadControl.Y),
                down(held, PadControl.LEFT_BUMPER), down(held, PadControl.RIGHT_BUMPER),
                down(held, PadControl.LEFT_STICK_BUTTON), down(held, PadControl.RIGHT_STICK_BUTTON),
                down(held, PadControl.START), down(held, PadControl.BACK), down(held, PadControl.GUIDE));
    }

    private boolean down(Set<Integer> held, PadControl control) {
        int key = keys[control.ordinal()];
        return key != 0 && held.contains(key);
    }

    private float[] stick(Set<Integer> held, PadControl left, PadControl right, PadControl up, PadControl down) {
        float x = (down(held, right) ? 1f : 0f) - (down(held, left) ? 1f : 0f);
        float y = (down(held, down) ? 1f : 0f) - (down(held, up) ? 1f : 0f);
        float length = (float) Math.hypot(x, y);
        return length > 1f ? new float[] {x / length, y / length} : new float[] {x, y};
    }

    /**
     * Why a key cannot be given to a control, or empty when it can. The keys that move focus, press buttons or cancel
     * must stay what they are, or a person could no longer leave the viewport with the keyboard.
     */
    public static Optional<String> reservedReason(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_TAB -> Optional.of("Tab moves between controls");
            case KeyEvent.VK_ESCAPE -> Optional.of("Escape cancels");
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> Optional.of("Enter and Space press the focused button");
            case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> Optional.of("Backspace and Delete clear a binding");
            case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META, KeyEvent.VK_WINDOWS,
                    KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_CONTEXT_MENU -> Optional.of("Modifier keys cannot be used on their own");
            default -> Optional.empty();
        };
    }

    /** What a key is called on the keyboard: {@code W}, {@code Left}, {@code Numpad 4}. */
    public static String keyName(int keyCode) {
        return keyCode == 0 ? "Not set" : KeyEvent.getKeyText(keyCode);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyboardMap map && Arrays.equals(keys, map.keys);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keys);
    }

    @Override
    public String toString() {
        return "KeyboardMap" + bindings();
    }
}
