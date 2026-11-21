/**
 * What rewritten robot bytecode calls, and the seam through which the host answers. {@link
 * dev.ftcsim.contracts.hooks.Hooks} is the static facade: one volatile read and a call on the installed
 * {@link dev.ftcsim.contracts.hooks.RobotHooks}, which the kernel implements for the clock, sleeps, waits, yields,
 * monitors and loop back edges. {@link dev.ftcsim.contracts.hooks.TimeHooks} and {@link
 * dev.ftcsim.contracts.hooks.NetworkHooks} replace the JDK's ways of reading the date and using the network; each has
 * the signature of the JDK member it replaces, a constructor becoming a factory. {@link
 * dev.ftcsim.contracts.hooks.RobotUnwind} and {@link dev.ftcsim.contracts.hooks.RobotExit} end a robot thread that must
 * stop. The stand-ins the hooks hand out (the simulated clock, the simulated listeners and sockets) are here too,
 * because the robot world reaches them through these hooks. A simulated {@code ServerSocket} on a port the host names
 * ({@link dev.ftcsim.contracts.hooks.RobotHooks#listenOnLoopback}) is a real listener on the loopback address.
 *
 * <p>This package belongs to both class loaders, so it must stay free of the simulation, the SDK and the UI. See
 * {@code docs/architecture/decisions.md}, sections 2 and 10.
 */
package dev.ftcsim.contracts.hooks;
