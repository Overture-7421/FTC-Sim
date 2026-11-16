/**
 * The virtual-time kernel. {@link dev.ftcsim.core.kernel.SimulationKernel} is the only place simulated time moves: it
 * advances the fixed-rate world ({@link dev.ftcsim.core.kernel.FixedRateWorld}, whose tasks run at exact
 * integer-nanosecond instants), runs the scheduled callbacks and then runs the robot thread, which a baton
 * ({@code RobotGate}) lets run only between the simulator's moves. {@code KernelHooks} is what the rewritten robot
 * bytecode reaches: clock reads, sleeps, yields, monitors and loop back edges. {@link dev.ftcsim.core.kernel.KernelConfig}
 * says what each of them costs in simulated time, and {@link dev.ftcsim.core.kernel.KernelDiagnostics} records what the
 * kernel noticed about the robot thread that the user should hear of. Threads of the robot world other than the robot
 * thread, such as a library's timer, run in real time beside the simulation; the kernel does not hold time back for
 * them.
 *
 * <p>Nothing here reads a real clock. See {@code docs/architecture/decisions.md}, sections 2, 9 and 10.
 */
package dev.ftcsim.core.kernel;
