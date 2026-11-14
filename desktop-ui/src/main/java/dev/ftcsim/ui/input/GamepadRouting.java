package dev.ftcsim.ui.input;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides, from what a person set and what is plugged in, where each of the two gamepads listens. Pure: the same
 * settings and controllers always give the same answer, and a controller or the keyboard is never given to two
 * gamepads at once.
 *
 * <p>Controllers named in the settings are matched first, by model, in joystick order, so that two identical
 * controllers go to gamepad 1 and gamepad 2 in the order they were plugged in. Then the keyboard, for a gamepad set to
 * it. Then {@link PadAssignment.Kind#AUTOMATIC} gamepads take the first controller nobody has, and gamepad 1 falls back
 * to the keyboard when none is left and nothing else uses it. A gamepad that cannot have what was asked for listens
 * to nothing and says why.
 */
public final class GamepadRouting {
    private static final int GAMEPADS = 2;

    /** Where a gamepad's input comes from. */
    public enum Source {
        NONE,
        KEYBOARD,
        CONTROLLER
    }

    /**
     * @param padId       the joystick slot of the controller, or -1
     * @param description what the gamepad listens to, in words: a controller's name, {@code Keyboard}, or
     *                    {@code nothing (why)}
     * @param problem     what is wrong with what the person asked for, or null
     */
    public record Resolved(Source source, int padId, String description, String problem) {
        public boolean hasProblem() {
            return problem != null;
        }

        Resolved withProblem(String text) {
            return new Resolved(source, padId, description, text);
        }
    }

    private GamepadRouting() {}

    /** The resolution for gamepad 1 and gamepad 2, in that order. */
    public static Resolved[] resolve(GamepadConfig config, List<PadReading> readings) {
        List<PadReading> usable = new ArrayList<>();
        for (PadReading reading : readings) {
            if (reading.mapped()) {
                usable.add(reading);
            }
        }
        usable.sort(Comparator.comparingInt(PadReading::id));

        Resolved[] result = new Resolved[GAMEPADS];
        Set<Integer> claimed = new HashSet<>();
        boolean sameControllerTwice = false;
        boolean keyboardTwice = false;
        boolean keyboardClaimed = false;

        for (int slot = 0; slot < GAMEPADS; slot++) {
            PadAssignment assignment = config.pad(slot + 1).assignment();
            if (assignment.kind() != PadAssignment.Kind.CONTROLLER) {
                continue;
            }
            PadReading found = firstUnclaimed(usable, claimed, assignment.guid());
            if (found != null) {
                claimed.add(found.id());
                result[slot] = controller(found);
            } else if (usable.stream().anyMatch(reading -> assignment.guid().equals(reading.guid()))) {
                sameControllerTwice = true;
                result[slot] = new Resolved(Source.NONE, -1, "nothing (" + nameOf(assignment) + " drives the other gamepad)", null);
            } else {
                result[slot] = new Resolved(Source.NONE, -1, "nothing (" + nameOf(assignment) + " is not connected)", null);
            }
        }
        for (int slot = 0; slot < GAMEPADS; slot++) {
            PadAssignment.Kind kind = config.pad(slot + 1).assignment().kind();
            if (kind == PadAssignment.Kind.KEYBOARD) {
                if (keyboardClaimed) {
                    keyboardTwice = true;
                    result[slot] = new Resolved(Source.NONE, -1, "nothing (the keyboard drives the other gamepad)", null);
                } else {
                    keyboardClaimed = true;
                    result[slot] = keyboard();
                }
            } else if (kind == PadAssignment.Kind.NONE) {
                result[slot] = new Resolved(Source.NONE, -1, "nothing (set to None)", null);
            }
        }
        for (int slot = 0; slot < GAMEPADS; slot++) {
            if (config.pad(slot + 1).assignment().kind() != PadAssignment.Kind.AUTOMATIC) {
                continue;
            }
            PadReading found = firstUnclaimed(usable, claimed, null);
            if (found != null) {
                claimed.add(found.id());
                result[slot] = controller(found);
            } else if (slot == 0 && !keyboardClaimed) {
                keyboardClaimed = true;
                result[slot] = keyboard();
            } else {
                result[slot] = new Resolved(Source.NONE, -1, "nothing (no controller is free)", null);
            }
        }
        if (sameControllerTwice) {
            for (int slot = 0; slot < GAMEPADS; slot++) {
                result[slot] = result[slot].withProblem("Gamepads 1 and 2 are both set to the same controller, but only one is connected.");
            }
        }
        if (keyboardTwice) {
            for (int slot = 0; slot < GAMEPADS; slot++) {
                result[slot] = result[slot].withProblem("The keyboard can drive only one gamepad at a time.");
            }
        }
        return result;
    }

    private static PadReading firstUnclaimed(List<PadReading> usable, Set<Integer> claimed, String guid) {
        for (PadReading reading : usable) {
            if (!claimed.contains(reading.id()) && (guid == null || guid.equals(reading.guid()))) {
                return reading;
            }
        }
        return null;
    }

    private static Resolved controller(PadReading reading) {
        return new Resolved(Source.CONTROLLER, reading.id(), reading.displayName(), null);
    }

    private static Resolved keyboard() {
        return new Resolved(Source.KEYBOARD, -1, "Keyboard", null);
    }

    private static String nameOf(PadAssignment assignment) {
        return assignment.name().isBlank() ? "The controller" : assignment.name();
    }
}
