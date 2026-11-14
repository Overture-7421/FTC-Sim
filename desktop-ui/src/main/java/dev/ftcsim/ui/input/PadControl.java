package dev.ftcsim.ui.input;

/** The controls of an FTC gamepad that the keyboard can stand in for, in the order a person would list them. */
public enum PadControl {
    LEFT_STICK_UP("Left stick", "up"),
    LEFT_STICK_DOWN("Left stick", "down"),
    LEFT_STICK_LEFT("Left stick", "left"),
    LEFT_STICK_RIGHT("Left stick", "right"),
    RIGHT_STICK_UP("Right stick", "up"),
    RIGHT_STICK_DOWN("Right stick", "down"),
    RIGHT_STICK_LEFT("Right stick", "left"),
    RIGHT_STICK_RIGHT("Right stick", "right"),
    LEFT_TRIGGER("Triggers and bumpers", "left trigger"),
    RIGHT_TRIGGER("Triggers and bumpers", "right trigger"),
    LEFT_BUMPER("Triggers and bumpers", "left bumper"),
    RIGHT_BUMPER("Triggers and bumpers", "right bumper"),
    DPAD_UP("D-pad", "up"),
    DPAD_DOWN("D-pad", "down"),
    DPAD_LEFT("D-pad", "left"),
    DPAD_RIGHT("D-pad", "right"),
    A("Buttons", "A"),
    B("Buttons", "B"),
    X("Buttons", "X"),
    Y("Buttons", "Y"),
    LEFT_STICK_BUTTON("Buttons", "left stick button"),
    RIGHT_STICK_BUTTON("Buttons", "right stick button"),
    START("Buttons", "Start"),
    BACK("Buttons", "Back"),
    GUIDE("Buttons", "Guide");

    private final String group;
    private final String name;

    PadControl(String group, String name) {
        this.group = group;
        this.name = name;
    }

    /** What the control belongs to: {@code Left stick}, {@code D-pad}, {@code Buttons}. */
    public String group() {
        return group;
    }

    /** What it is called within its group: {@code up}, {@code left trigger}, {@code A}. */
    public String shortName() {
        return name;
    }

    /** The name in full, for a message or a screen reader: {@code Left stick up}. */
    public String label() {
        return group.equals("Buttons") || group.equals("Triggers and bumpers")
                ? Character.toUpperCase(name.charAt(0)) + name.substring(1)
                : group + " " + name;
    }
}
