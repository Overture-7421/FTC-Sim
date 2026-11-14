package dev.ftcsim.hardware.lynx;

/** Validates the channel numbers the host passes when wiring up a hub. */
final class Indexes {

    private Indexes() {}

    static int check(String what, int index, int count) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException(what + " must be 0.." + (count - 1) + ": " + index);
        }
        return index;
    }
}
