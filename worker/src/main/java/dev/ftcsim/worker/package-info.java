/**
 * The worker process of the simulator: {@link dev.ftcsim.worker.WorkerMain} connects back to the program that started
 * it and serves the {@code SimulatorLink} port with a real-time simulation, one session at a time. Everything that
 * runs robot code, the physics engine and the simulated hardware lives in this process, so that a robot program that
 * hangs or crashes, or a fault in a native library, ends this process and nothing else.
 */
package dev.ftcsim.worker;
