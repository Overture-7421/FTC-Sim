package dev.ftcsim.contracts.session;

/** One line of what the hardware is doing, for a panel that shows it: a name and what to show for it. */
public record Readout(String name, String value) {}
