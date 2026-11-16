package dev.ftcsim.contracts.session;

/**
 * A motor a robot mapping may name.
 *
 * @param id          the id the mapping and the motor catalogue use
 * @param displayName what to call it in a window
 */
public record MotorChoice(String id, String displayName) {}
