# The link between the UI and the simulation

How the window and the simulation worker talk (launch, wire, threads, refusals, catalogue, timed match); read it if you change either side.

## Modules

- `sim-contracts` (`dev.ftcsim.contracts.session`) holds the port, `SimulatorLink`; `desktop-ui` depends on this only.
- `sim-link` (`dev.ftcsim.link`) is the UI's side and the wire format; it depends on `sim-contracts` only. `app` gives `desktop-ui` a `RemoteSimulatorLink`.
- `worker` (`dev.ftcsim.worker`) is the process that runs the simulation.

Why a separate process: [decisions.md](decisions.md) section 6. Tests serve the same port in process; `RealtimeRunner` is thread-safe for it.

## Launching

`new WorkerLauncher(classpath, logDirectory).launch()` returns a connected `RemoteSimulatorLink`. Launch properties are in the Javadoc of `WorkerLauncher`; what the application passes is in [app.md](app.md).

- The launcher listens on `127.0.0.1` (ephemeral port) and starts the worker's JVM.
- The class path travels in an argument file in the computer's code page, because a Windows command line holds about 32 000 characters. A path the code page cannot write is refused, naming the offending letter (`ClasspathFile` is the format).
- The worker connects back and says hello: magic `FTCS`, protocol version, launch token, process id. The token is random per launch and goes to the worker on standard input, never on a command line.
- A connection with another token is dropped without an answer. Another protocol version gets a refusal and fails the launch.
- Worker output (standard out and error) goes to `worker-<time>-<id>.log` in the log directory (capped at 16 MiB) and to a ring of the last 200 lines. When a worker starts, files of all but the newest 10 launches (`.log`, `.crash.log`, `.args`) are deleted.
- The worker ends when the connection closes, on `Close`, or when the launcher's process is gone. Closing the link asks it to end, waits `shutdownGrace` (3 s by default), then `destroyForcibly()` on exactly that process.

## The wire

Hand-written, big-endian, no serialisation framework, deterministic (floats and doubles travel as IEEE bits, so a `WorldSnapshot` comes back bit for bit).

- Every length is checked against a bound before anything is allocated. Unknown content, a message that ends early or has bytes left over is an error, never skipped.
- **The current version is 13** (`Protocol.VERSION`). Snapshots end with the AprilTag ids in the Limelight's latest
  completed frame. Field parts carry opacity after their RGB colour (it affects drawing,
  not sensors) and a shape may be a wedge, a triangular prism for gables. The version changes whenever a message gains, loses or reorders a field. The format and the version list are
  in the package description of `dev.ftcsim.link.wire`.
- The worker never reads `ftcsim.json`: it builds the robot with `RobotDesign.forField(request.field()).spec(request.mapping())` from the mapping `Open` carries.
- The worker tees `System.out`, `System.err` (`LineCapture`) and the SDK's log calls into a `WorkerLog` sink that sends them in order as `Log` events (a `Log` is never dropped for a newer one); the window shows them as its console. The worker does not log what it sends, so a failing connection cannot feed itself.

## Threads and what the UI can rely on

- **No call blocks.** Commands are queued and a writer thread sends them in order, except that when the writer is behind only the latest state of each gamepad is sent (it keeps its place among the commands).
- **One thread calls the listeners**, `ftcsim-link-dispatcher`, one call at a time.
  - A slow listener gets the latest snapshot and status, not every one it missed; a new session makes what waited from the old one stale.
  - A listener that throws does not stop the others; one that calls the link from inside a callback is fine.
- `addListener` takes effect in order with the events: the listener hears what arrives after the call, and once the **catalogue** that arrived before it.
- **`refused(message)`: a command was refused and changed nothing.** The session, if any, goes on exactly as it was; show the message and carry on. A refusal is never a crash of the worker. The worker refuses when:
  - no session is open;
  - an OpMode, human action or gamepad does not exist;
  - a match is armed or running and the command would disturb it;
  - Start has nothing initialised;
  - the simulation says no (with a sentence a person can act on).
- **`failed(message)`: about the session or the worker, never about one command.** A requested session could not be opened (a mapping with problems, which the message lists; a program that is not there; a field the worker does not know), or the open session cannot go on, or the worker is gone.
  - While `isUsable()` is true, a new `open` or a `reset` may be tried.
  - **`reset` is in place:** the robot program stays loaded and only the world is rebuilt (`SimulationSession.resetWorld`). Hubs, devices and wiring are kept, as a real robot's electronics are when carried back to its start; simulated time goes on. It takes milliseconds where `open` takes seconds. The worker answers with `opened` again.
  - **When the worker dies**, `failed` comes once, with how it ended and the last lines of its error output; `isUsable()` turns false and later calls do nothing.
- **Which commands wait for the simulation.** Init, Start, a human action and the two match commands are decided on the simulation's own thread, because only it knows the answer (the match, the robot controller). The worker's thread waits for that decision, so a refusal reaches the UI as `refused`, not as a line in `problems`. Stop can never be refused and does not wait. The simulation never calls into the worker, so nothing the UI does can hold it up.

## The catalogue

Right after the handshake the worker sends a `Catalogue` once (the UI cannot ask for anything before it):

- for each field (from `FieldRegistry`) a `FieldChoice`: `id` as a `SessionRequest` takes it, display name, start poses, human actions; `none` (bare floor, no start poses) comes first;
- the motors a robot mapping may name (`MotorChoice`, from `MotorCatalog`, default first).

The UI applies a start pose by copying `x`, `y`, `headingRadians` into `startX`, `startY`, `startHeading` of the request.

## The timed match

The simulator plays a match in **simulated time**, never the UI's clock, so pause and speed apply (`TimedMatch` in `simulator`; autonomous 30 s, transition 8 s, driver-controlled 120 s, constants there).

- `armMatch(auto, teleOp)` needs an idle robot and no match so far; `startMatch()` starts it; `stop()` gives up an armed match or ends one under way at once.
- The field has no transition phase and counts it as autonomous.
- `SessionStatus.match` is `MatchStatus(state, phaseElapsedNanos, phaseDurationNanos)`; the UI shows that instead of a clock of its own. States: NONE, ARMED, AUTONOMOUS, TRANSITION, TELEOP, ENDED; elapsed and duration are 0 in NONE, ARMED and ENDED. Free practice never leaves NONE.
- While a match is armed or under way `init` and `start` are refused. After it ends practice may follow; another match needs `reset`.
- A robot that crashes or cannot be started does not stop the clock; what the robot controller refused is added to the status `problems`.
- The UI should offer arming only when the robot is IDLE and the match state is NONE, and starting only when ARMED. Whatever slips through comes back as `refused`; only `failed` ends what is on screen.
