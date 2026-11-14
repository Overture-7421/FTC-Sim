package dev.ftcsim.contracts.robot;

/**
 * An OpMode a robot program declares.
 *
 * @param name      the name a driver selects, unique within the program
 * @param group     the optional grouping label from the annotation, empty when absent
 * @param kind      teleop or autonomous
 * @param className binary name of the implementing class
 */
public record OpModeDescriptor(String name, String group, OpModeKind kind, String className) {}
