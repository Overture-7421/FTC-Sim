package dev.ftcsim.ui.input;

import java.util.Objects;

/**
 * What a gamepad is told to listen to. {@link Kind#AUTOMATIC} is the default: the first controller nobody else has is
 * taken, and gamepad 1 falls back to the keyboard when there is none. A controller is named by the model's GUID, which
 * stays the same when it is plugged in again, and by its name for people to read.
 */
public record PadAssignment(Kind kind, String guid, String name) {
    /** The ways a gamepad can be fed. */
    public enum Kind {
        AUTOMATIC,
        KEYBOARD,
        NONE,
        CONTROLLER
    }

    public static final PadAssignment AUTOMATIC = new PadAssignment(Kind.AUTOMATIC, "", "");
    public static final PadAssignment KEYBOARD = new PadAssignment(Kind.KEYBOARD, "", "");
    public static final PadAssignment NONE = new PadAssignment(Kind.NONE, "", "");

    public PadAssignment {
        Objects.requireNonNull(kind, "kind");
        guid = guid == null ? "" : guid;
        name = name == null ? "" : name;
    }

    public static PadAssignment controller(String guid, String name) {
        return new PadAssignment(Kind.CONTROLLER, guid, name);
    }
}
