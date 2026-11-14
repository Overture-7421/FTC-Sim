/**
 * The UI's side of the process boundary: {@link dev.ftcsim.link.RemoteSimulatorLink} implements the port of
 * {@code sim-contracts} over a loopback socket to a worker process, and {@link dev.ftcsim.link.WorkerLauncher} starts
 * that process. The bytes on the socket are described in {@link dev.ftcsim.link.wire}.
 */
package dev.ftcsim.link;
