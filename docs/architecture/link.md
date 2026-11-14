# The link between the UI and the simulation

Two modules. `sim-link` is the UI's side and the wire format (package `dev.ftcsim.link`; depends on `sim-contracts`
only). `worker` is the process that runs the simulation (package `dev.ftcsim.worker`; depends on `simulator`,
`sim-link`, the games and `project-import`). The port, `SimulatorLink`, is in `sim-contracts`
(`dev.ftcsim.contracts.session`), so the window needs nothing else: the application (`app`) gives it a
`RemoteSimulatorLink` from `sim-link`, and `desktop-ui` itself depends on `sim-contracts` only (`app` -> `sim-link` ->
`sim-contracts`).

## Why a process

A robot program that loops, crashes or exhausts memory, or a fault in the native physics engine, must not take the UI
down, and a fresh worker is the cleanest reset. In process, for tests, the same port is served directly: `RealtimeRunner`
is thread-safe for it.

## Launching

`new WorkerLauncher(classpath, logDirectory).launch()` returns a connected `RemoteSimulatorLink`:

1. The launcher listens on `127.0.0.1`, ephemeral port, and starts the worker's JVM (`java` found through
   `ProcessHandle`, unless one is given) with `--enable-native-access=ALL-UNNAMED` and
   `--sun-misc-unsafe-memory-access=allow`. The class path travels in an argument
   file in the computer's code page, because a Windows command line holds about 32 000 characters; a path that the code
   page cannot write is refused with the letter that is the problem. The physics engine's native library is unpacked
   into `%LOCALAPPDATA%\FTC-Sim\natives` unless `nativeDirectory` says otherwise.
2. The worker connects back and says hello: magic `FTCS`, protocol version, the launch token, its process id. The token is
   random, per launch, and goes to the worker on standard input, never on a command line. A connection with another
   token is dropped without an answer; another protocol version is answered with a refusal and fails the launch.
3. Standard output and error of the worker go to `worker-<time>-<id>.log` in the log directory and to a ring of the last
   200 lines. When a worker starts, the files of all but the newest 10 launches (`.log`, `.crash.log` of the Java
   runtime, `.args`) are deleted from that directory; a file that cannot be deleted stays.
4. The worker ends when the connection closes, on `Close`, or when the launcher's process is gone. Closing the link asks
   the worker to end, waits `shutdownGrace` (3 s by default), then `destroyForcibly()` on exactly that process.

The application launches it with its own Java, the jars of its class path file, `%LOCALAPPDATA%\FTC-Sim\natives` and
`...\logs`: see `app.md`. The same file format (`ClasspathFile`) is what a prepared robot program's `program.properties`
is read in.

## The wire

Hand-written, big-endian, no reflection or serialisation framework, deterministic: floats and doubles travel as their
IEEE bits, so a `WorldSnapshot` comes back bit for bit. A frame is a 32-bit length and that many bytes, the first of which
is the kind of message. Every length is checked against a bound (16 MiB a frame, 1 MiB a text, 100 000 entries a list)
and against the bytes that remain before anything is allocated. A kind that this version does not know, a message that
ends early or has bytes left over, an enumeration code that does not exist and a flag that is not 0 or 1 are errors, never
skipped. The layout of every message is the table in the package description of `dev.ftcsim.link.wire`.

The protocol version changes whenever a message gains, loses or reorders a field. **Version 2** added `ArmMatch`,
`StartMatch`, the `Catalogue` event and the match at the end of `Status`. **Version 3** added the `Refused` event; the
decoder no longer judges a gamepad number, the worker does.

## Threads and what the UI can rely on

- **No call blocks.** Commands are queued; a writer thread sends them in the order given, except that of each gamepad
  only the latest state is sent when the writer is behind (it keeps its place among the commands).
- **One thread calls the listeners**, `ftcsim-link-dispatcher`, one call at a time. A listener that is slow is handed the
  latest snapshot and the latest status, not every one it missed; a new session makes what waited from the old one stale.
  A listener that throws does not stop the others.
- `addListener` takes effect in order with the events: the listener hears what arrives after the call, and the
  **catalogue** that arrived before it, once.
- **`refused(message)` is a command that was refused and changed nothing.** The session, if there is one, goes on exactly
  as it was; show the message and carry on. The worker refuses a command when no session is open, when an OpMode or a
  human action does not exist, when a match is armed or running and the command would disturb it, when a match cannot be
  armed or started, when Start has nothing initialised, when a gamepad does not exist, and when the simulation says no.
  The message is a sentence a person can act on, in the simulation's own words.
- **`failed(message)` is about the session or the worker, never about one command.** A session that was asked for could
  not be opened (a profile or program that is not there, a field the worker does not know), or the open session cannot go
  on (the simulation stopped), or the worker is gone. While `isUsable()` is still true a new `open` or a `reset` may be
  tried. When the connection drops or the worker exits it comes once, with how the worker ended and the last lines of its
  error output, `isUsable()` turns false and later calls do nothing.
- A refused command is never a crash of the worker. The worker checks what the UI sends and answers with a message that
  says what there is (the OpModes of the program, the actions of the field).
- **Which commands wait for the simulation.** Init, Start, a human action and the two match commands are decided by the
  simulation's own thread, because the answer depends on what only it knows (the match, the robot controller). The
  worker's thread waits for that decision, so a refusal reaches the UI as `refused` and not as a line in `problems`. Stop
  can never be refused and does not wait. Nothing the UI does can hold the simulation up: the simulation never calls into
  the worker.

## The catalogue

Before a session is open the UI has to know what it can ask for. Right after the handshake the worker sends a
`Catalogue`: for each field a `FieldChoice` (`id` as a `SessionRequest` takes it, display name, the start poses the
field offers, the human actions it lists) and the names of the bundled robot profiles. `none`, the bare floor, comes first
and has no start poses. Fields come from the worker's `FieldRegistry`, profiles from `RobotProfileLoader.bundledNames()`.
Today it cannot change, so it is sent once; the listener method is called again if it ever does. A start pose is applied
by the UI: it copies `x`, `y` and `headingRadians` into `startX`, `startY` and `startHeading` of the request.

## The timed match

A match is played by the simulator in **simulated time**, never by the UI's clock, so pausing and speed apply to it and a
different slicing of time gives the same match (`TimedMatch` in `simulator`; every boundary is a callback scheduled for
its exact instant). The lengths are named constants there: autonomous 30 s, transition 8 s, driver-controlled 120 s.

| step | what happens |
|---|---|
| `armMatch(auto, teleOp)` | needs the idle robot and no match so far; initialises the autonomous OpMode, or the driver-controlled one when `auto` is empty; state ARMED |
| `startMatch()` | state AUTONOMOUS (TELEOP without an autonomous period); starts the OpMode; tells the field `AUTONOMOUS` (or `TELEOP`) |
| 30 s later | stops the autonomous OpMode, initialises the driver-controlled one; state TRANSITION (the field has no such phase and counts it as autonomous) |
| 8 s later | starts the driver-controlled OpMode; tells the field `TELEOP`; state TELEOP |
| 120 s later | tells the field `ENDED`, stops the OpMode; state ENDED; the score goes on settling by the field's own rules |
| `stop()` | ARMED: the match is given up, state NONE. Under way: ends at once as at its end |

`SessionStatus.match` reports `MatchStatus(state, phaseElapsedNanos, phaseDurationNanos)`; elapsed and duration are 0 in
NONE, ARMED and ENDED. Free practice never leaves NONE and keeps the field's `PRACTICE` phase. While a match is armed or
under way `init` and `start` are refused with `refused`; after it has ended, practice may follow, but another match
needs `reset`. A robot that crashes or cannot be started does not stop the clock: what the robot controller refused is
added to the status `problems`.

## What the UI must do

- Implement `Listener.catalogue` and build its choosers from it: fields, start poses, human actions, profiles.
- Show `SessionStatus.match` (state and `phaseElapsedNanos` against `phaseDurationNanos`) instead of a clock of its own.
- Offer arming only when the robot is IDLE and the match state is NONE, starting only when it is ARMED, and keep Init and
  Start away from an armed or running match. Whatever slips through comes back as `refused`: show the message, keep the
  session. Only `failed` ends what is on screen.
- A listener that is slow loses nothing it needs; one that calls the link from inside a callback is fine.

## Tests

`sim-link`: codec round trips of every message and malformed input (truncated, bad length, wrong token, wrong version,
unknown kinds and codes); the link against a fake worker over a real socket (ordering, coalescing, latest-wins,
single-threaded delivery, the catalogue for late listeners, refusals that do not end the link, failure on a dropped
connection); the launcher with real stand-in processes; log pruning. `worker`: the command loop on fake sessions (every
kind of refusal, and `Failed` only where it belongs), the factory, the resolvers. `acceptance-tests`:
`MatchSessionTest` (the match with the real SDK on the fixture OpModes) and `WorkerProcessTest`, tagged `two-process`
(the real worker, the real link, a robot that drives, a crash, refusals of Init, Start, arming and a human action, a
match played through, no process left behind).
