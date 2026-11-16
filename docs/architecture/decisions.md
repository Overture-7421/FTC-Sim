# Architecture decisions

Windows only. Every number below was measured on one development desktop unless it says otherwise; none of it has
been measured on a student laptop yet, and each section says what is still open.

"Gate" in a heading means one of five feasibility gates, each a prototype with a pass criterion that was run before the
choice it checks was built on: real SDK on a desktop JVM, virtual time, physics, Material 3 with a 3D viewport, and
start-up without Java. A "conditional pass" needed hardware this project did not have; the condition is under **Open**.

## 1. The stack: JVM end to end, one native physics engine

**Decision.** Java for the simulation, the hardware emulation and the SDK adapters; a small Kotlin layer for the
Material 3 UI; Jolt (through jolt-jni) for physics. No Rust.

**Why.** The robot program is Java and runs the real FTC SDK, so the hard compatibility work (virtual time,
bytecode rewriting, the SDK's Android assumptions) is on the JVM whatever the rest is written in. A Rust core would
add a second language and a per-call bridge between the robot program and the device models without removing any of
that work. Native code is used where it earns its place: the physics engine.

## 2. Two class loaders and virtual time

**Decision.** The host (kernel, physics, hardware emulation, UI) and the robot world (the team's program, the FTC
SDK, a clean-room `android.*` shim, `sdk-common`) are separate class loaders; only `dev.ftcsim.contracts.*` is
shared. The robot world's clock, sleeps, waits, monitors and loop back-edges are rewritten at load time to call the
simulation kernel, which moves simulated time only on the simulator thread and runs the robot thread between moves.
The real SDK's `OpModeManagerImpl` runs iterative and linear OpModes, busy waits and a stop that interrupts a sleep.

**What time costs.** Robot code moves simulated time only through hooks: a clock read costs 200 ns, a yield 1 µs, hub
traffic what its wire takes, and every backward jump `SimulationSession.LOOP_NANOS` = 100 ns, about one trivial loop
iteration on a Control Hub. The last one matters most: a loop that waits (`while (opModeInInit()) initialize_loop();`,
a student's `while (!gamepad1.a) {}`) reads no clock and touches no hardware, so only its jumps let time pass. At 1 ns
each it ran a billion iterations per simulated second and Hornet's Init ran at 0.38 times real time (64 % of the robot
thread's samples in the hook); at 100 ns it runs at about 8 times, as fast as the rest of the world allows, and the
Hornet autos score the same. Computation is charged too (a thousand iterations cost 100 µs), closer to a Control Hub
than free computation. `SessionSpeedTest` fails if the cost is made too small again.

**Open.** Section 10 says what has since been virtualised and what has not (the timed waits that library code makes
through the JDK run in real time). Section 9 says why the lockstep stays although repeatable runs are not promised.

## 3. Hardware is emulated at the wire

**Decision.** The SDK's own `LynxModule`, motor controllers and I2C drivers run unchanged. Only the USB device is
replaced: it hands the SDK's serialised RHSP datagrams to a pure-Java emulation of REV hub firmware. A new I2C device
is a model of that device attached to a hub bus; the real SDK driver for it then works as it does on a robot, on SDK
11 and 12 alike (the two SDKs' hub code is identical source).

**Threads.** The bus has one lock. The scheduled threads (the simulator and the robot thread) take turns at it. A
thread the kernel does not schedule, such as a library's own timer (Panels reads the battery voltage from one), talks to
the hubs through a separate entry, under the same lock, that never moves simulated time: queries are answered as the
hubs stand and leave no trace in them, and anything that would change a hub is applied. Nothing is flagged: such a
thread runs when the operating system lets it, and what it changes is then up to the machine (section 9).

## 4. Physics: Jolt (Gate 3, conditional pass)

**Decision.** Jolt through jolt-jni 7.0.0, single precision, one thread, 240 Hz fixed step. The natives are the exact
jolt-jni 7.0.0 release build, Jolt's cross-platform-deterministic one; the code refuses a build that is not (or is double
precision), but nothing promises a repeatable run (section 9). The DLL is no longer checked against a hash at run time,
and no longer probed for flush-to-zero modes: both served the repeatability promise that section 9 dropped. The DLL is
still unpacked into a folder named after its SHA-256, so that two builds never share a file, and a test asserts the
hash of the one that ships.

**Evidence.** A step costs about 0.15 ms against a 4.17 ms budget. The DLL imports only `KERNEL32`.

**Open.** There is no static drivetrain friction, and the chassis parameters are estimates, not fitted to a robot.

## 5. UI: Compose Material 3 over an offscreen 3D viewport (Gate 4, conditional pass)

**Decision.** The 3D scene is rendered offscreen by LWJGL OpenGL into a framebuffer, read back through pixel-buffer
objects and shown as a bitmap inside Compose, so every Material 3 element overlays it correctly.

**Evidence.** `viewport-measurements.md`: frame interval p99 7.0 ms at 144 Hz and 17.2 ms at 60 Hz, no stall above about
55 ms, 58 to 84 percent of one core, 0.9 to 1.4 ms per frame for readback and copy. Rejected: a native canvas in Compose
(overlays hidden, a native crash with window layers), a docked window (blanks on resize, cannot be overlaid), sharing
Skia's GL context (the driver refuses).

**Open.** Not measured on a 4-core integrated-GPU laptop or on a display above 60 Hz. Presentation latency is at
least two frames (29 to 38 ms at 60 Hz).

## 6. The simulation runs in a worker process

**Decision.** The UI holds a `SimulatorLink`, a port of commands in and snapshots and status out. A worker process
runs the simulation in real time on its own thread, the only place wall-clock time touches it.

**Why.** A crashing or runaway robot program, or a native fault in the physics engine, must not take the UI down, and a
new worker is the cleanest reset. Launching, wire format, threading and failure rules are in `link.md`; where the
application starts it from is in `app.md`.

**Open.** The worker is reached over loopback TCP guarded by a random per-launch token; that keeps strangers out of a
launch, it is not authentication against another program running as the same user. A session that does not stop within
20 s ends the worker.

## 7. The runtime comes with the program (Gate 5, conditional pass; reversed in phase 6)

**Decision.** A bundled runtime. `:app:dist` links a Java 25 runtime with `jlink` from the JDK the project is compiled
for (the Gradle toolchain's) into the `runtime` folder of the distribution, and `FTC-Sim.cmd` starts `runtime\bin\java.exe`
with the options of `runtime-options`. The user's installed JDKs are irrelevant to the simulator.

**Why it replaced download-first.** The first version was the owner's "download-first": a pinned Temurin 25.0.5+8,
checksum verified, fetched on the first start by PowerShell scripts that also looked for installed JDKs. It cost a first
start that needs the network (and a proxy, a firewall or a policy that allows PowerShell), about 1.5 thousand lines of
PowerShell and 88 tests that existed to make provisioning safe, a pin moved by hand with every Temurin release that
refused a PC's own newer build, and a Java on the PC that the simulator did not choose. The cost of the bundled
runtime is size: the zip is 35.6 MiB larger (44.4 to 80.0 MiB, measured).

**What is in the runtime.** `java.se` (every standard module, so a team library that needs one still runs) plus a few
modules it does not name (`sun.misc.Unsafe` for LWJGL and the SDK, a robot program's web server such as Panels', and
others): 51.3 MB unpacked in 200 files, 35.8 MB zipped, against 362 MB for the whole JDK. The floor that `jdeps` finds
over the window's, the worker's and Hornet's jars is 47.6 MB / 32.4 MB zipped, so `java.se` costs 3.7 MB for safety: a
missing module would be a `NoClassDefFoundError` in team code. Left out: `jdk.localedata` (+10 MB; the worker runs with
the en-US locale), the compiler, `jdk.jfr`, the debugging and monitoring tools.

**The JDK it is made from.** The build's own toolchain, so that the runtime is the Java the code was compiled and tested
on (`-Pftcsim.runtimeJdk=<JDK home>` names another of the same major version). A development PC's toolchain JDK may be
another vendor's (the measured PC has Oracle's 25.0.1, whose licence is not the GPL with the Classpath Exception), so a
release should be built from an Eclipse Temurin 25, by the owner's choice, not by the build.

**The hand-off.** `FTC-Sim.cmd` is a small batch file with no PowerShell: it checks that `runtime`, `lib\app.jar` and
`runtime-options` are beside it (else exit 79), runs `runtime\bin\java.exe` with `runtime-options`, `-XX:ErrorFile` into
`%LOCALAPPDATA%\FTC-Sim\logs` and `-jar lib\app.jar`, and returns the application's exit code. The application writes its
own log (`AppLog`), so no batch timestamp is needed. A double-click's console now stays open while FTC-Sim runs, because
a batch file cannot both wait for the program, to show a failure, and close its own console. Layout, launcher, self test
and licence notices: `distribution.md`.

**The importer's JDK.** Importing a source project runs the project's own Gradle build, which a 2024 project cannot run on
Java 25, so `JdkDiscovery` finds a JDK 17 to 21 of the user's (`app.md`). The bundled runtime does not change that.

**Open.** Not run on a clean machine or under a real application-control policy; whether SmartScreen or an antivirus
scanner treats an unpacked `java.exe` from a downloaded zip differently is not known. A folder name with characters
outside the Windows ANSI code page makes the Java launcher fail to open `app.jar`. The product owner still has to choose
a licence for FTC-Sim's own code: the repository has none, and none was invented.

## 8. Game pieces: zones on plants, owned exactly once

**Decision.** Mechanisms never push on the chassis. A motor is a plant on a hub port. Intakes, storages and
launchers are zones whose activation is a condition on named motors' shaft speeds, never on the robot program. A piece is
a free body in the physics world, carried by a storage (drawn from it, found by sensor rays analytically), or in
the field's reserve, and never two of these. An intake takes only the piece sizes it is built for (an optional
diameter range of the robot's design), which is how a robot built for one game's pieces meets another game's pieces
without picking them up by accident; the range is physical, so no game or piece name appears in a spec.

**Rolling resistance.** Jolt alone never stops a ball that rolls on the floor: it reuses contact points until bodies
have moved 1 mm, so a ball slower than 1 mm per step keeps a stale contact that drives the roll, and damping settles at
about 0.24 m/s for ever. Pieces whose spec sets a rolling resistance get fresh contacts every step and lose spin at the
rate a rolling resistance coefficient implies, so they stop in finite time and fall asleep. It is one mechanism in
`sim-physics` on `PieceSpec`, used by both fields; the coefficient (0.06) is an estimate, not a measurement.

## 9. Repeatability is not promised

**Decision (product owner).** Two runs of the same program, profile, field, seed and inputs are not promised to give
the same world, bit for bit or at all. Accuracy and repeatability were never the goal; the goal is to test a team's code
and to practise driving. Repeatability was promised before, and it cost more than it was worth: waiting in real time for
every library thread before time could move, rewriting every source of randomness, a state hash pinned across JVM flags,
and flags on runs the simulator could not vouch for. Each was machinery, and some of it (Panels' start-up thread against
the OpMode thread) was a source of waits and failures of its own. What is gone: waiting for foreign threads, the
reproducibility flags, randomness virtualisation, state hashes, and recording and replay. The field's own seed
(`FieldSetup.seed`: reserve order, layout noise) stays, since a field is laid out from it.

**What stays: lockstep virtual time.** The robot thread runs only when the simulator hands it the baton and gives it back
at its next hook (`SimulationKernel`, `RobotGate`, `VirtualClock`, `FixedRateWorld`; the rewrites of `TimeRules` and
`RewriteRules`). It stays because it is what makes the product work, not because it makes runs repeat: the run can be
paused, slowed and fast-forwarded without the robot code noticing (a sleep or a loop is simulated time, never real time);
a loop iteration costs simulated time rather than however long the machine took, so loop timing is steady; and the world,
the hubs and the devices have one writer at a time and need no locks. An OpMode sees the same sleep durations and
readings however the caller slices time, and tests assert that on outcomes (a time, a pose within a tolerance), not on
hashes.

**Kept, because the OpMode needs it to run, stop and unwind, not to repeat.** The monitor settle (a robot thread
entering or leaving a wait is briefly in a state the simulator cannot read); detection of a robot thread blocked in
something the simulator does not control (`RobotGate.State.EXTERNAL`); unwinding and abandoning a robot thread and
`System.exit` handling (section 10); a robot waiter's notification by another thread held until the baton is granted;
`settle` after the host starts or wakes the robot thread; and the bus's lock. The problems the kernel still reports are
about the robot thread (it would not end, it came back from an uncontrolled wait), not about repeatability.

**What can now differ between two runs.** The interleaving of a library's own threads (Panels' start-up thread and its
ticker, a `Timer`) with the OpMode, which can move an OpMode's hub transmission across a hub tick; the start-up latency
of a thread; random numbers; the iteration order of identity-hashed sets. A robot program that waits on a library thread
(Hornet's Panels start-up) spins its loop a different number of times each run, and each spin costs simulated time, so
two runs start a few microseconds apart. Most runs are identical; now and then an autonomous, which makes many discrete
decisions, ends on a different score (Hornet's far autonomous classified 9 artifacts on most runs and 6 on some). Tests
that play a whole autonomous therefore ask that it reaches its bar within three runs (`HornetAutoRunTest`); a broken
robot reaches it in none. The Hornet tests assert outcomes, not worlds.

## 10. The robot world's contact with the machine

What a robot program can reach outside its class loader, and what the simulator answers. The rule behind it all: the
robot's clock is the simulated clock, and nothing it reaches touches the machine's network. Where the simulator cannot
answer for the robot thread (it would not end, it came back from a wait the simulator does not control), the run says so,
as a problem (`KernelDiagnostics.problems()`, `SimulationSession.problems()`). Randomness and the timed waits of library
code are the machine's own (section 9).

**Network policy.** The robot world never listens or connects on the real network: a listening socket makes Windows
ask a student about its firewall, and traffic makes a run depend on the outside. Every JDK way to bind, listen, connect
or send is rewritten to `NetworkHooks` (the table is `NetworkRules`; a test fails when a JDK member that reaches the
network is missing from it). Listening (`ServerSocket`, `ServerSocketChannel`) gets a *simulated listener*: it holds the
port in a private network of the robot world, binds nothing on the machine, `accept` waits until it is closed, and no
client can connect. Each such request is recorded (`NetworkHooks.simulatedRequests()`); everything else is declined,
fails the way a closed port fails, and is recorded (`declinedRequests()`). Hornet's Panels listens on 8001 and 8002 and
on 5800, 5801, 5805 and 5807 (the Limelight proxy), all simulated. Binding the wildcard address is left out on purpose,
since it could itself make Windows ask. When a session closes the world's listeners close too; a thread that goes on
accepting on its closed socket waits for good instead of failing again and again (Panels' proxy otherwise logged 2.8
million lines in a few seconds).

**Lifecycle hooks.** `ControllerLifecycle` does what the Robot Controller app does around the robot's life: it calls
the libraries' `@OnCreate` hooks, gives them the event loop (`@OnCreateEventLoop`) and an in-process web handler
manager (`@WebHandlerRegistrar`), and calls `@OnDestroy` at the end, through the SDK's own `AnnotatedHooksClassFilter`.
The SDK logs and swallows what a hook throws, so a failed hook becomes a warning on the Driver Station, and so does a
thread that dies of an unhandled exception.

**The APK index.** Libraries ask the app what it contains (the SDK's `ClassManager`, Panels' `ClassFinder`); on a device
that is the APK's `classes.dex`. The simulator writes a real zip whose `classes.dex` is a class-name index only
(`ApplicationPackage`, `DexIndex`), read through the shim's `dalvik.system.DexFile`; the classes load from the program.
A class that cannot be linked here (for want of Android's user interface) is reported with a hundred lines of stack (8,000
for Hornet), so SDK and library classes are one line with the count and the commonest reasons and the team's own stay in
full (`ScanLog`, `RobotProgram.teamClasspath()`).

**Runaway OpModes and `System.exit`.** An OpMode that ignores Stop is given the SDK's own timeouts in simulated time
(`RunawayGuard`): the stuck-code timeout, then 100 ms during which the hubs' bus lock throws at whatever loop touches a
hub. Pausing, slowing or fast-forwarding a run can neither trip nor delay them. After that `RobotUnwind`, an `Error`, is
thrown at the robot thread's next hook (a clock read, sleep, wait, yield or loop back edge), threads blocked in the JDK
are interrupted, and a thread that swallows the error is asked again every 250 ms, four times. One that still does not
end is abandoned: parked for good at its next hook, recorded in `KernelDiagnostics.abandonedThreads()` and as a problem,
and the SDK's wait for it is released so that the session can run another OpMode. `System.exit`, `Runtime.exit` and
`Runtime.halt` from robot code never end the JVM: the call throws `RobotExit` (a `RobotUnwind`), the request is recorded
with its status and thread, the controller shows an error and stops the OpMode, and the program's shutdown hooks are
remembered and never run. Both are problems of the session.

**Time.** Every robot-visible source of the wall clock comes from the simulated clock (`KernelConfig.epochMillis`,
2026-01-01T00:00:00Z by default), by table-driven rewrites (`TimeRules`, whose tests scan the JDK so that a new `now()`
is noticed): `Date`, `Calendar.getInstance`, `GregorianCalendar`, `Clock.system*` and `tick*`, and the `now()` of every
`java.time` class. Only the instant is virtualised. *Not* virtualised: randomness (`Random`, `Math.random`,
`UUID.randomUUID`, `SecureRandom`, `ThreadLocalRandom`), the timed waits that library code makes through the JDK
(`Timer`, `Semaphore.tryAcquire`, `BlockingQueue.poll`, `Condition.await`, `LockSupport.parkNanos`, `Thread.join`),
`Runtime.availableProcessors`, the machine's zone and locale, file timestamps.

**Foreign threads.** A thread of the robot world other than the robot thread (Panels' starter and ticker, a library's
timer) runs in real time; the kernel neither schedules nor waits for it. Its hub traffic goes through
`transmitUnscheduled` (section 3), and its `notify` of a monitor the robot thread waits on is held until the simulator
grants the baton. What the kernel still does is about running, stopping and unwinding the robot thread: it waits for it
to park, end or block when the simulator acts on it (`settle`); it follows a lock chain, so that a thread waiting for a
lock a *running* thread holds is waited for, while one blocked on something the simulator does not control (`EXTERNAL`)
is left, time going on without it, and reported as a problem when it comes back. A robot thread that the SDK starts
inside a Stop (the next OpMode's) is not waited for: its real start-up counts as simulated time, a fraction of a
millisecond on a quiet machine and tens on a loaded one, which is why the runaway tests allow 250 ms of slack. Panels'
start-up thread filling its configurables list while the OpMode thread walks it is a race in Panels' own code that the
simulator no longer hides; if a Hornet run shows it again, run that thread on the simulated clock, do not bring the
waiting back.

**Host shutdown.** Closing a session closes the world's listeners, interrupts every thread whose context class loader
is the robot world's and waits one second. A thread that ignores the interrupt cannot be killed; it is left running and
reported as a problem of the closed session (Hornet leaves three: `Timer-0`, the Limelight proxy and Panels' ticker).
The production worker ends with the session, so only a host that runs several sessions in one JVM carries them.

**Open.** Library timers and timed waits on the simulated clock; ending the threads that swallow interrupts (a
tracked `Timer` that the host cancels); the zone and locale of the simulated machine; `Runtime.availableProcessors`.

## 11. What was left out, and why

Each of these was considered and decided against; adding one back is the product owner's call.

- **Replay and recording of runs.** Built, then removed: nothing in the product needed it. A new run is a new session.
- **Bit-exact repeatability and golden hashes.** Built, then removed by the owner: accuracy and repeatability were never
  the goal, and the machinery cost waits, flaky corners and tests of its own. Lockstep virtual time stayed (section 9).
- **TOML and YAML robot files.** One format, JSON (`ftcsim.json`, read and written whole by `ProjectMappingsImpl`): no
  second parser to keep in step.
- **Air drag.** The owner judged it excessive for what the shot tests need. The launcher is fitted by its angle and
  efficiency to the shooter table of the robot (`robots/hornet-fit.md`), and a far shot scores by banking off the back
  of the goal, as real ones do (`fields/decode.md`, "Friction, bounce and bank shots").
- **Mechanisms that push on the chassis** (section 8), **foul and ranking-point rules, an opposing robot or a second robot**,
  and **features for other seasons**: the aim is to test a team's code and to practise driving, not to referee or to
  design a robot.
- **A browser dashboard for Panels.** Its server asks to listen and gets a simulated listener that nobody can connect to
  (section 10); the program runs, the page does not exist.
- **A Rust core or sidecar** (section 1) and **platforms other than Windows**.
