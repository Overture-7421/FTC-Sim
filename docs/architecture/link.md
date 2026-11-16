# The link between the UI and the simulation

Two modules. `sim-link` is the UI's side and the wire format (package `dev.ftcsim.link`; depends on `sim-contracts` only).
`worker` is the process that runs the simulation (package `dev.ftcsim.worker`). The port, `SimulatorLink`, is in
`sim-contracts` (`dev.ftcsim.contracts.session`), so `desktop-ui` depends on `sim-contracts` only: the application (`app`)
gives it a `RemoteSimulatorLink` from `sim-link`.

## Why a process

A robot program that loops, crashes or exhausts memory, or a fault in the native physics engine, must not take the UI
down, and a fresh worker is the cleanest reset. In process, for tests, the same port is served directly: `RealtimeRunner`
is thread-safe for it.

## Launching

`new WorkerLauncher(classpath, logDirectory).launch()` returns a connected `RemoteSimulatorLink`; the launch properties
are in the Javadoc of `WorkerLauncher`; what the application passes is in `app.md`.

- The launcher listens on `127.0.0.1`, ephemeral port, and starts the worker's JVM. The class path travels in an argument
  file in the computer's code page, because a Windows command line holds about 32 000 characters; a path that the code page
  cannot write is refused with the letter that is the problem (`ClasspathFile` is the format).
- The worker connects back and says hello: magic `FTCS`, protocol version, the launch token, its process id. The token is
  random, per launch, and goes to the worker on standard input, never on a command line. A connection with another token is
  dropped without an answer; another protocol version is answered with a refusal and fails the launch.
- The worker's standard output and error go to `worker-<time>-<id>.log` in the log directory and to a ring of the last 200
  lines. When a worker starts, the files of all but the newest 10 launches (`.log`, `.crash.log`, `.args`) are deleted.
- The worker ends when the connection closes, on `Close`, or when the launcher's process is gone. Closing the link asks it
  to end, waits `shutdownGrace` (3 s by default), then `destroyForcibly()` on exactly that process.

## The wire

Hand-written, big-endian, no serialisation framework, deterministic (floats and doubles travel as their IEEE bits, so a
`WorldSnapshot` comes back bit for bit). Every length is checked against a bound before anything is allocated, and anything
this version does not know, or a message that ends early or has bytes left over, is an error, never skipped. **The current
version is 7** (`Protocol.VERSION`), and the format and the list of versions are documented in the package description of
`dev.ftcsim.link.wire`. The version changes whenever a message gains, loses or reorders a field. The worker never reads
`ftcsim.json`: it builds the robot with `RobotDesign.forField(request.field()).spec(request.mapping())` from the mapping
that `Open` carries.

The worker tees `System.out`, `System.err` (`LineCapture`) and the SDK's log calls into a `WorkerLog` sink that sends them in
order as `Log` events (a `Log` is never dropped for a newer one), which the window shows as its console. The worker does not
log what it sends, so a failing connection cannot feed itself.

## Threads and what the UI can rely on

- **No call blocks.** Commands are queued; a writer thread sends them in the order given, except that of each gamepad only
  the latest state is sent when the writer is behind (it keeps its place among the commands).
- **One thread calls the listeners**, `ftcsim-link-dispatcher`, one call at a time. A slow listener is handed the latest
  snapshot and the latest status, not every one it missed; a new session makes what waited from the old one stale. A
  listener that throws does not stop the others, and one that calls the link from inside a callback is fine.
- `addListener` takes effect in order with the events: the listener hears what arrives after the call, and the
  **catalogue** that arrived before it, once.
- **`refused(message)` is a command that was refused and changed nothing.** The session, if there is one, goes on exactly as
  it was; show the message and carry on. The worker refuses when no session is open, when an OpMode, a human action or a
  gamepad does not exist, when a match is armed or running and the command would disturb it, when Start has nothing
  initialised, and when the simulation says no, with a sentence a person can act on. A refusal is never a crash of the worker.
- **`failed(message)` is about the session or the worker, never about one command.** A session that was asked for could not
  be opened (a mapping with problems, which the message lists, a program that is not there, a field the worker does not
  know), or the open session cannot go on, or the worker is gone. While `isUsable()` is still true a new `open` or a `reset`
  may be tried. **`reset` is in place:** the robot program stays loaded and only the world is built again
  (`SimulationSession.resetWorld`); the hubs, devices and wiring are kept, as a real robot's electronics are when it is
  carried back to its start, and simulated time goes on, so it takes milliseconds where `open` takes seconds. The worker
  answers with `opened` again. **When the worker dies**, `failed` comes once, with how it ended and the last lines of its
  error output, `isUsable()` turns false and later calls do nothing.
- **Which commands wait for the simulation.** Init, Start, a human action and the two match commands are decided by the
  simulation's own thread, because the answer depends on what only it knows (the match, the robot controller). The worker's
  thread waits for that decision, so a refusal reaches the UI as `refused` and not as a line in `problems`. Stop can never
  be refused and does not wait. Nothing the UI does can hold the simulation up: the simulation never calls into the worker.

## The catalogue

Right after the handshake the worker sends a `Catalogue` (the UI cannot ask for anything before it): for each field a
`FieldChoice` (`id` as a `SessionRequest` takes it, display name, start poses, human actions), `none` (the bare floor,
without start poses) first, and the motors a robot mapping may name (`MotorChoice`, the default first). Fields come from the
worker's `FieldRegistry`, motors from `MotorCatalog`. It is sent once. A start pose is applied by the UI: it copies `x`, `y`
and `headingRadians` into `startX`, `startY` and `startHeading` of the request.

## The timed match

A match is played by the simulator in **simulated time**, never by the UI's clock, so pausing and speed apply to it
(`TimedMatch` in `simulator`; the lengths are constants there: autonomous 30 s, transition 8 s, driver-controlled 120 s).
`armMatch(auto, teleOp)` needs the idle robot and no match so far; `startMatch()` starts it; `stop()` gives up an armed match
or ends one under way at once. The field has no transition phase and counts it as autonomous. `SessionStatus.match` reports
`MatchStatus(state, phaseElapsedNanos, phaseDurationNanos)` (NONE, ARMED, AUTONOMOUS, TRANSITION, TELEOP, ENDED; elapsed and
duration are 0 in NONE, ARMED and ENDED), so the UI shows that instead of a clock of its own. Free practice never leaves NONE.
While a match is armed or under way `init` and `start` are refused; after it has ended, practice may follow, but another match
needs `reset`. A robot that crashes or cannot be started does not stop the clock: what the robot controller refused is added
to the status `problems`.

The UI should offer arming only when the robot is IDLE and the match state is NONE and starting only when it is ARMED;
whatever slips through comes back as `refused`, and only `failed` ends what is on screen.