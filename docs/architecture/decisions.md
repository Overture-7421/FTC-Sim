# Architecture decisions

Windows only. Every number below was measured on one development desktop unless it says otherwise; none of it has
been measured on a student laptop yet, and each section says what is still open.

"Gate" in a heading means one of five feasibility gates, each a prototype with a pass criterion that was run before the
choice it checks was built on: (1) the real SDK and an unmodified team program run on a desktop JVM, (2) clock and
scheduling are repeatable, (3) physics and determinism, (4) Material 3 together with a 3D viewport, (5) start-up without
Java and the laptop budget. A "conditional pass" is a pass whose last condition needs hardware this project did not have;
the condition is named under **Open**.

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

**Evidence.** The real SDK's `OpModeManagerImpl` runs iterative and linear OpModes, busy waits and a stop that
interrupts a sleep, with identical results under different run slicings (acceptance tests, repeated runs).

**What time costs.** Robot code moves simulated time only through hooks: a clock read costs 200 ns, a yield 1 µs, hub
traffic what its wire takes, and every backward jump of the code `SimulationSession.LOOP_NANOS` = 100 ns, about one
trivial loop iteration on a Control Hub. The last one matters more than it looks: a loop that waits (a command OpMode's
`while (opModeInInit()) initialize_loop();`, a student's `while (!gamepad1.a) {}`) reads no clock and touches no hardware,
so only its jumps let time pass. At 1 ns each it ran a billion iterations for every simulated second, and Hornet's Init
ran at 0.38 times real time on a Ryzen 9 9950X (a profile put 64 % of the robot thread's samples in the hook); at 100 ns it
runs at about 8 times, as fast as the rest of the world allows, and the Hornet autos score the same. Computation is
charged too (a thousand loop iterations cost 100 µs), which is closer to a Control Hub than free computation was.
`SessionSpeedTest` fails if the cost is made too small again.

**Open.** Section 10 says what has since been virtualised (the SDK's stuck-code timeouts, a runaway OpMode, `Date`,
`Instant` and `Random`) and what has not: the timed waits that library code makes through the JDK.

## 3. Hardware is emulated at the wire

**Decision.** The SDK's own `LynxModule`, motor controllers and I2C drivers run unchanged. Only the USB device is
replaced: it hands the SDK's serialised RHSP datagrams to a pure-Java emulation of REV hub firmware. A new I2C device
is a model of that device attached to a hub bus; the real SDK driver for it then works as it does on a robot, on SDK
11 and 12 alike (the two SDKs' hub code is identical source).

**Evidence.** 220 hub tests at first, now more than 370 with the device models; a real SDK OpMode drives motors,
servos and bulk-cached reads end to end.

**Threads.** The bus is deterministic for the scheduled threads (the simulator and the robot thread). A thread the
kernel does not schedule, such as a library's own timer (Panels reads the battery voltage from one), talks to the hubs
through a separate entry that never moves simulated time: queries are answered as the hubs stand, anything that would
change a hub is applied and flagged, and the host reports a flagged run as not reproducible.

## 4. Physics: Jolt (Gate 3, conditional pass)

**Decision.** Jolt through jolt-jni 7.0.0, single precision, deterministic natives, one thread, 240 Hz fixed step.

**Evidence.** The golden hash trace is bit-identical across the default JIT, `-Xint`, C1-only, the debug natives and
a fresh in-process world. A step costs about 0.15 ms against a 4.17 ms budget. The DLL imports only `KERNEL32`.

**Open.** The golden test has not run on an Intel laptop or a second AMD machine. There is no static drivetrain
friction, and the chassis parameters are estimates, not fitted to a robot.

## 5. UI: Compose Material 3 over an offscreen 3D viewport (Gate 4, conditional pass)

**Decision.** The 3D scene is rendered offscreen by LWJGL OpenGL into a framebuffer, read back through pixel-buffer
objects and shown as a bitmap inside Compose, so every Material 3 element overlays it correctly.

**Evidence.** See `viewport-measurements.md`. Frame interval p99 7.0 ms at a 144 Hz render rate and 17.2 ms at 60 Hz,
no stall above about 55 ms, 58 to 84 percent of one core, 0.9 to 1.4 ms per frame for readback and copy. Rejected:
a native canvas embedded in Compose (overlays hidden or blank, and a native crash with window layers), a separate docked
window (blanks on resize, cannot be overlaid), sharing Skia's GL context (the driver refuses).

**Open.** Not measured on a 4-core integrated-GPU laptop or on a display above 60 Hz. Presentation latency is at
least two frames (29 to 38 ms at 60 Hz).

## 6. The simulation runs in a worker process

**Decision.** The UI holds a `SimulatorLink`, a port of commands in and snapshots and status out. A worker process
runs the simulation in real time on its own thread, the only place wall-clock time touches it.

**Why.** A crashing or runaway robot program, or a native fault in the physics engine, must not take the UI down, and a
new worker is the cleanest reset.

**Evidence.** `WorkerProcessTest` starts the real worker: the UI's link drives the fixture robot, a crashing robot
program does not end the worker or the link, a timed match is played through, a killed worker is reported once, and no
process is left behind when the link closes. How the worker is launched, the wire format, the threading and failure
rules, the catalogue and the timed match are in `link.md`; where the application starts it from, and with what, is in
`app.md`.

**Open.** The worker is reached over loopback TCP guarded by a random per-launch token; that keeps strangers out of a
launch, it is not authentication against another program running as the same user. A session that does not stop within
20 s ends the worker.

## 7. Provisioning and runtimes (Gate 5, conditional pass)

**Decision.** Download-first JDK provisioning, as the product owner asked: a pinned Temurin download, checksum
verified, unpacked into a per-user cache (Temurin 25: 134.7 MiB download, 6.2 s cold and 1.6 s warm on a fast link).
Two JDK pins: the simulator runs on JDK 25 (Foreign Function API final); importing a source project runs the project's
own Gradle build on a JDK that project supports.

**The launcher enforces the simulator's pin, to the exact build.** `launcher\scripts\jdk-manifest.json` names major 25
and the release `jdk-25.0.5+8` with its SHA-256 (`runtime.exactRelease`, `runtime.exactSha256`), so that the same inputs
give the same outputs on the same Java (section 9). Stage 0 starts the application only on a runtime for x64 Windows
whose `java.runtime.version` is exactly that build, a JDK or a JRE of any vendor (the simulator needs `java.exe`, not a
compiler). A JDK 17, 21, 24 or 26, or another build of 25 (Oracle 25.0.1 on the development PC), found through
`JAVA_HOME`, `PATH`, the registry, Program Files or Android Studio is never used silently: `JAVA_HOME` is reported and
ignored, the others are named with the reason before the download ("... is not used: build 25.0.1+8-LTS-27 is not the
pinned build 25.0.5+8"), `-ListJdks` shows every candidate, and when none qualifies that exact Temurin release is
downloaded by name and refused unless its SHA-256 is the pinned one (a service that answers with another release or
hash is refused before any download, exit 86). `-RuntimePolicy Any` (25 or newer, any build) is a developer diagnostic
that nothing passes. The pin moves only on purpose, with `launcher\tools\Update-JdkPin.ps1` and three steps
(`distribution.md`); the values were checked against the Adoptium API (`latest` and by release name), the
`.sha256.txt` and the asset digest on GitHub, and a download of the real zip.

**The hand-off to the application.** There is no Java launcher between the script and the application: stage 0 has
already proven the runtime by running it (version, x64), so it starts `javaw.exe -jar lib\app.jar` itself, with the
options of the manifest's `application.jvmArguments` (native access, Unsafe), which the self test checks against the
running JVM. `app.jar` carries the class path of the window in its manifest; a `lib\*` wildcard would also put the
worker's and the robot world's jars (which have an `android` package of their own) on the window's class path, in no
defined order. A hidden `cmd /c` gives the program one shared handle for output and errors, so everything it writes,
and the Java runtime's own crash report (`-XX:+ErrorFileToStdout`), lands in `%LOCALAPPDATA%\FTC-Sim\logs\app-<time>.log`
in order, and is shown on the console meanwhile. The application's exit code is the exit code of `FTC-Sim.cmd`; started
by double-click, the console closes once the window has run for a few seconds, and a failure keeps it open. `Main
--self-test` checks the installation without a window. See `distribution.md`.

**Evidence.** 88 launcher tests: hostile archives, truncated and stalled downloads, concurrent launches, offline,
proxy and execution-policy cases each give a distinct message; JDK 17, 21, 24 and 26 and another build of 25 are all
refused, and a machine with only JDK 21 gets the pinned Temurin download; `Stage0PinTest` (9) holds the pin itself
(its shape, the messages for ignored runtimes, a release or hash that the service does not confirm, a release it no
longer has, the update tool); the hand-off (options, log, exit code, double-click) is tested against a stand-in
`javaw.exe`. In `app`, 16 of 63 tests check the distribution as built and start the real one through `FTC-Sim.cmd` on a
PC with no Java on the PATH (self test, exit code, an exception and a real JVM crash report all in the log; with the
unmodified manifest, the pinned build from the user's cache passes the self test and Oracle's 25.0.1 is refused with
its reason). The real Temurin 25.0.5+8 zip was downloaded, verified against the pin, unpacked and run by the real
`FTC-Sim.cmd --self-test` on the development PC: 7 s from `FTC-Sim.cmd` to "self-test passed" with the download, 1.4 s
with the JDK in the cache. No test downloads the real JDK.

**Licence notices.** The distribution carries `THIRD-PARTY-NOTICES.txt` and the full licence texts, generated by
`:app:thirdPartyNotices` from the POMs of the jars that fill `lib`; a jar whose licence the build cannot determine
fails the build with its name. The downloaded Temurin has its own section (GPL 2 with the Classpath Exception). The
product owner still has to choose a licence for FTC-Sim's own code: the repository has none, and none was invented.

**Open.** Not run on a clean machine or under a real application-control policy. A jlinked driver-only runtime
(33.7 MiB zipped, measured on Temurin 25) is a release-time option for machines where PowerShell or the network is
blocked, and the Temurin 25 JRE (55.9 MiB download against 134.7 MiB for the JDK) is a manifest change away, since
discovery already accepts a JRE. A cache left by an earlier launcher with Temurin 21, or by an earlier pin, is ignored,
not removed.

## 8. Game pieces: zones on plants, owned exactly once

**Decision.** Mechanisms never push on the chassis. A motor is a plant on a hub port. Intakes, storages and
launchers are zones whose activation is a condition on named motors' shaft speeds, never on the robot program. A piece is
a free body in the physics world, carried by a storage (drawn from it, found by sensor rays analytically), or in
the field's reserve, and never two of these. An intake takes only the piece sizes it is built for (an optional
diameter range in the robot profile), which is how a robot built for one game's pieces meets another game's pieces
without picking them up by accident; the range is physical, so no game or piece name appears in a profile.

**Rolling resistance.** Jolt alone never stops a ball that rolls on the floor: it reuses contact points until bodies
have moved 1 mm, so a ball slower than 1 mm per step keeps a stale contact that drives the roll, and damping settles at
about 0.24 m/s for ever. Pieces whose spec sets a rolling resistance get fresh contacts every step and lose spin at the
rate a rolling resistance coefficient implies, so they stop in finite time and fall asleep. It is one mechanism in
`sim-physics` on `PieceSpec`, used by both fields; the coefficient (0.06) is an estimate, not a measurement.

## 9. Determinism boundary

Same program, profile, field, seed and inputs, on the same pinned Windows, JDK and native physics build, give the
same run: the tests compare the physics world's state hash across different slicings of simulated time. Equality
across different CPUs is tested, not promised. Recording and replaying a session is not a feature (it was built and
removed as not needed).

## 10. The robot world's contact with the machine

What a robot program can reach outside its class loader, and what the simulator answers. The rule behind it all:
nothing the robot sees may depend on the machine, the network or real time. Where that cannot be had, the run says so,
as a problem (`KernelDiagnostics.problems()`, `SimulationSession.problems()`), rather than claiming a repeatable run.
This section replaces the open items of section 2.

**Network policy.** The robot world never listens or connects on the real network: a listening socket makes Windows
ask a student about its firewall, and traffic makes a run depend on the outside. Every JDK way to bind, listen, connect
or send is rewritten to `NetworkHooks` (the table is `NetworkRules`; a test fails when a JDK member that reaches the
network is missing from it). Listening, with a `ServerSocket` or a `ServerSocketChannel`, gets a *simulated listener*:
it holds the port in a private network of the robot world, binds nothing on the machine, `accept` waits until it is
closed, and no client can ever connect. Each such request is recorded as a fact (`NetworkHooks.simulatedRequests()`);
everything else is declined, fails the way a closed port fails, and is recorded (`declinedRequests()`). Hornet's Panels
asks to listen on 8001 and 8002 (its web server and socket) and 5800, 5801, 5805 and 5807 (the Limelight proxy), all
simulated. `HornetNetworkTest` checks it on a real run: while an OpMode runs, a real socket can bind each of those
ports on loopback (a positive control shows that a real listener would make the bind fail), and `netstat -ano` lists no
socket of the test process before or during the run. Binding the wildcard address is left out on purpose, since it
could itself make Windows ask. When a session closes the world's listeners close too, as the end of the robot's
process would close them; a thread that goes on accepting on its closed socket waits for good instead of failing
again and again (Panels' proxy otherwise logs 2.8 million lines in a few seconds).

**Lifecycle hooks.** `ControllerLifecycle` does what the Robot Controller app does around the robot's life: it calls
the libraries' `@OnCreate` hooks, gives them the event loop (`@OnCreateEventLoop`) and an in-process web handler
manager (`@WebHandlerRegistrar`), and calls `@OnDestroy` at the end; the SDK's own `AnnotatedHooksClassFilter` finds
and calls them. The SDK logs and swallows what a hook throws, so a failed hook becomes a warning on the Driver
Station, and so does a thread that dies of an exception nobody handled; the Hornet bring-up asserts that none shows.

**The APK index.** Libraries ask the app what it contains: the SDK's `ClassManager` to find OpModes and hooks,
Panels' `ClassFinder` to find its plugins. On a device that is the `classes.dex` of the APK. The simulator writes a
real zip whose `classes.dex` is a class-name index only (`ApplicationPackage`, `DexIndex`), made from the directories
and jars of the program and read through the shim's `dalvik.system.DexFile`; the classes themselves load from the
program. The scan reports each class that cannot be linked here, for want of parts of Android's user interface, with a
hundred lines of stack (8,000 lines for Hornet). The classes of the SDK and of libraries are now one line with the
count and the commonest reasons, and the team's own classes stay in full (`ScanLog`; the team's entries are
`RobotProgram.teamClasspath()`, and the three-argument constructor keeps every class in full).

**Runaway OpModes.** An OpMode that ignores Stop is given the SDK's own timeouts measured in simulated time
(`RunawayGuard`): the stuck-code timeout, then 100 ms during which the hubs' bus lock throws at whatever loop touches a
hub. Pausing, slowing or fast-forwarding a run can neither trip nor delay them. After that the robot thread is
unwound by `RobotUnwind`, an `Error`, thrown at its next hook (a clock read, sleep, wait, yield or loop back edge),
threads blocked in the JDK are interrupted, and a thread that swallows the error is asked again every 250 ms, four
times. One that still does not end is abandoned: parked for good at its next hook, recorded in
`KernelDiagnostics.abandonedThreads()` and as a problem, and the SDK's wait for it is released so that the session can
run another OpMode. `System.exit`, `Runtime.exit` and `Runtime.halt` from robot code never end the JVM: the call throws
`RobotExit` (a `RobotUnwind`), the request is recorded with its status and thread, the controller shows an error and
stops the OpMode, and the program's shutdown hooks are remembered and never run. Both are problems of the session.

**Time and randomness.** Every robot-visible source of the wall clock and of randomness comes from the simulated
clock and a seed (`KernelConfig.epochMillis`, 2026-01-01T00:00:00Z by default, and `seed`). The rewrites are
table-driven (`TimeRules`, `RandomRules`, whose tests scan the JDK so that a new `now()` is noticed): `new Date()`,
`Calendar.getInstance`, `new GregorianCalendar`, `Clock.system*` and `tick*`, `Instant.now()` and the `now()` and
`now(ZoneId)` of every class of `java.time`; `new Random()`, `new SplittableRandom()`, `Math.random`,
`StrictMath.random`, `UUID.randomUUID`, `Collections.shuffle(list)`, every way to a `SecureRandom` (a deterministic
`SimulatedSecureRandom`) and `ThreadLocalRandom.current()`. Direct calls, method references, lambdas and the `super()`
of a subclass of `Random` or `Date` are covered. A generator the program seeds itself is its own business. Only the
instant is virtualised: the time zone and the locale stay the machine's. The robot thread draws its seeds from a stream
that nothing else touches, so what a library's thread asks for never changes what the robot gets.
`ThreadLocalRandom` keeps its state in the thread, adds twice the thread's id to it at each draw and can be set only
through `sun.misc.Unsafe` (JDK 25 warns once, and `--sun-misc-unsafe-memory-access=allow` silences it). The seed is set
at each `current()`, so `ThreadLocalRandom.current().nextInt()` follows the seed whatever the thread's id, while a
generator that is kept and drawn from again drifts with it; a JVM that forbids the write makes it a problem.
*Not* virtualised: the timed waits that library code makes through the JDK (`Timer`, `Semaphore.tryAcquire`,
`BlockingQueue.poll`, `Condition.await`, `LockSupport.parkNanos`, `Thread.join`), `Runtime.availableProcessors`, the
machine's zone and locale, file timestamps.

**Identity hashes.** Objects that do not override `hashCode` are hashed by a generator each thread owns, whose start
depends on how many threads the JVM made before, and a `HashSet` or `HashMap` of them iterates in that order (SolversLib's
command scheduler is one). The order is the same in two processes of one JVM build with the same flags and differs
between flags (`-Xint` against the default; `IdentityHashOrderTest`). It is not fixed, only recorded: the claim of
repeatable runs is for one JVM build and one set of flags. A program that depends on the order can differ across
machines that JIT-compile differently, so far not seen: Hornet's Far Auto gives the same world with and without the JIT
(`HornetCrossJvmTest`).

**Foreign threads.** A thread of the robot world other than the robot thread, such as Panels' starter and ticker or a
library's timer, runs in real time and the kernel does not schedule it. It must never change what the robot sees.
First, its hub traffic goes through `transmitUnscheduled`: it never moves simulated time, queries are answered as the
hubs stand, and a command that would change a hub is applied and flagged as a problem. Second, the kernel never lets
such a thread run beside simulated time or beside the robot thread's turn: before time moves, before a callback and
before it hands the robot thread the baton, the simulator waits in real time until every thread that runs robot code
(a frame of the class loader named `robot` on its stack; a thread started a moment ago is given 100 ms to show its
first frame) is at rest, at most 20 s, then a problem (`WorldThreads`). What such a thread did is finished and the
same however fast the machine ran. The failure this cures: Panels' start-up thread filled its configurables list
while the OpMode thread walked the same list; each loop iteration costs a hundred nanoseconds of simulated time
(`SimulationSession.LOOP_NANOS`; it was one nanosecond when this was found), so the OpMode's hub transmissions moved
with the number of iterations it made, now and then across a 1 ms hub tick, and one run in two gave another world.
The robot thread is likewise waited for when it is starting, and when it waits for a lock that a running thread holds;
one that is blocked on something the simulator does not control is a problem. A foreign thread that wakes while the
robot thread runs, such as a `Timer` firing, is not held back: that is the residual risk, shown by
`KernelDiagnostics.worldWaits()`, and it goes only when library timers run on the simulated clock.

**Host shutdown.** Closing a session closes the world's listeners, interrupts every thread whose context class loader
is the robot world's (every call into the controller runs with it set, so every thread the program starts inherits it)
and waits one second. A thread that ignores the interrupt cannot be killed; it is left running and reported as a
problem of the closed session. Hornet leaves three: `Timer-0` (a `TimerThread` swallows interrupts), the Limelight
proxy and Panels' ticker. The production worker is a process of its own that ends with the session, so only a host
that runs several sessions in one JVM carries them.

**Open.** Library timers and timed waits on the simulated clock; ending the threads that swallow interrupts (a
tracked `Timer` that the host cancels); the zone and locale of the simulated machine; `Runtime.availableProcessors`.

## 11. What was left out, and why

Each of these was considered and decided against; adding one back is the product owner's call.

- **Replay and recording of runs.** Built, then removed: nothing in the product needed it. A new run is a new session,
  and the state hash plus the tests that compare it across slicings of time are what remain (section 9).
- **TOML and YAML robot profiles.** One format, JSON, parsed strictly (`RobotProfileLoader`): no second parser to keep in
  step. A `ftcsim.toml` that an earlier tool left in the Hornet checkout was replaced by a copy of the JSON profile.
- **Air drag.** The owner judged it excessive for what the shot tests need. The launcher is fitted by its angle and
  efficiency to the shooter table of the robot (`robots/hornet-profile.md`), and a far shot scores by banking off the back
  of the goal, as real ones do (`fields/decode.md`, "Friction, bounce and bank shots").
- **Mechanisms that push on the chassis** (section 8), **foul and ranking-point rules, an opposing robot or a second robot**,
  and **features for other seasons**: the aim is to test a team's code and to practise driving, not to referee or to
  design a robot.
- **A browser dashboard for Panels.** Its server asks to listen and gets a simulated listener that nobody can connect to
  (section 10); the program runs, the page does not exist.
- **A Rust core or sidecar** (section 1) and **platforms other than Windows**.
