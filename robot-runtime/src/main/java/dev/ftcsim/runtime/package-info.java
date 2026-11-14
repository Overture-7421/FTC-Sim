/**
 * The robot world's class loader and the load-time rewriting that keeps what a robot program can observe from
 * depending on the machine.
 *
 * <p>{@link dev.ftcsim.runtime.RobotClassLoader} defines the robot program, the FTC SDK and every library in a world
 * of their own, child-first from the robot class path, and passes each class through a
 * {@link dev.ftcsim.runtime.BytecodeTransformer}. {@link dev.ftcsim.runtime.HookRewriter} is the one that matters: it
 * redirects the JDK calls named by the rule tables to the static methods of {@code dev.ftcsim.contracts.hooks}, tells
 * the hooks when a thread takes or gives up a monitor, and calls {@code Hooks.backEdge} at every backward jump, so that
 * a loop that only waits lets simulated time pass. A class that cannot be transformed is an error, never loaded as it
 * is, because an uninstrumented class would escape the virtual clock.
 *
 * <p>The rule tables are code: {@code RewriteRules} (clock, sleep, yield, wait and notify, native libraries),
 * {@code TimeRules}, {@code RandomRules} and {@code NetworkRules}. To virtualise another JDK member, add a row to the
 * table, give the hooks class a method with the descriptor the row implies, and add a probe class and a test; the rule
 * tests scan the JDK, so a member that reads the clock, makes randomness or reaches the network and is missing from its
 * table fails the build. See {@code docs/architecture/decisions.md}, sections 2 and 10.
 */
package dev.ftcsim.runtime;
