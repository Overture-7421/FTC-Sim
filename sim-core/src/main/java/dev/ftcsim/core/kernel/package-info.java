/**
 * The virtual-time kernel. {@link dev.ftcsim.core.kernel.SimulationKernel} is the only place simulated time moves: it
 * advances the fixed-rate world ({@link dev.ftcsim.core.kernel.FixedRateWorld}, whose tasks run at exact
 * integer-nanosecond instants), runs the scheduled callbacks and then runs the robot thread, which a baton
 * ({@code RobotGate}) lets run only between the simulator's moves. {@code KernelHooks} is what the rewritten robot
 * bytecode reaches: clock reads, sleeps, yields, monitors and loop back edges. {@link dev.ftcsim.core.kernel.KernelConfig}
 * says what each of them costs in simulated time, {@link dev.ftcsim.core.kernel.KernelDiagnostics} records everything
 * that weakens determinism, and {@code WorldThreads} keeps threads of the robot world that the kernel does not
 * schedule from running beside simulated time.
 *
 * <p>Nothing here reads a real clock. See {@code docs/architecture/decisions.md}, sections 2 and 10.
 */
package dev.ftcsim.core.kernel;
