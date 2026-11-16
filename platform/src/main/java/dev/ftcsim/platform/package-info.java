/**
 * What the simulator and the window need from Windows itself: a high-resolution timer for holding a thread to a cadence,
 * a parker that uses it (or the JDK's own park where it cannot be opened) and the limiter and deadline waiter on top.
 * Nothing here knows about the simulation or the screen.
 */
package dev.ftcsim.platform;
