# Architecture decisions

Windows only. Every number below was measured on one development desktop unless it says otherwise; none of it has
been measured on a student laptop yet, and each section says what is still open.

"Gate" in a heading means one of five feasibility gates, each a prototype with a pass criterion that was run before the
choice it checks was built on: (1) the real SDK and an unmodified team program run on a desktop JVM, (2) clock and
scheduling in virtual time, (3) physics, (4) Material 3 together with a 3D viewport, (5) start-up without
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
interrupts a sleep, and an OpMode sees the same times and readings under different run slicings (acceptance tests).

**What time costs.** Robot code moves simulated time only through hooks: a clock read costs 200 ns, a yield 1 µs, hub
traffic what its wire takes, and every backward jump of the code `SimulationSession.LOOP_NANOS` = 100 ns, about one
trivial loop iteration on a Control Hub. The last one matters more than it looks: a loop that waits (a command OpMode's
`while (opModeInInit()) initialize_loop();`, a student's `while (!gamepad1.a) {}`) reads no clock and touches no hardware,
so only its jumps let time pass. At 1 ns each it ran a billion iterations for every simulated second, and Hornet's Init
ran at 0.38 times real time on a Ryzen 9 9950X (a profile put 64 % of the robot thread's samples in the hook); at 100 ns it
runs at about 8 times, as fast as the rest of the world allows, and the Hornet autos score the same. Computation is
charged too (a thousand loop iterations cost 100 µs), which is closer to a Control Hub than free computation was.
`SessionSpeedTest` fails if the cost is made too small again.

**Open.** Section 10 says what has since been virtualised (the SDK's stuck-code timeouts, a runaway OpMode, `Date` and
`Instant`) and what has not: the timed waits that library code makes through the JDK, which run in real time beside the
simulation. Section 9 says why the lockstep stays although repeatable runs are no longer promised.

## 3. Hardware is emulated at the wire

**Decision.** The SDK's own `LynxModule`, motor controllers and I2C drivers run unchanged. Only the USB device is
replaced: it hands the SDK's serialised RHSP datagrams to a pure-Java emulation of REV hub firmware. A new I2C device
is a model of that device attached to a hub bus; the real SDK driver for it then works as it does on a robot, on SDK
11 and 12 alike (the two SDKs' hub code is identical source).

**Evidence.** 220 hub tests at first, now more than 370 with the device models; a real SDK OpMode drives motors,
servos and bulk-cached reads end to end.

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

## 7. The runtime comes with the program (Gate 5, conditional pass; reversed in phase 6)

**Decision.** A bundled runtime. `:app:dist` links a Java 25 runtime with `jlink` from the JDK the project is compiled
for (the Gradle toolchain's) into the `runtime` folder of the distribution, and `FTC-Sim.cmd` starts `runtime\bin\java.exe`
with the options of `runtime-options`. The first version of this decision was the product owner's "download-first": a
pinned Temurin 25.0.5+8, checksum verified, fetched on the first start by PowerShell scripts that also looked for
installed JDKs, with an exact-build pin. It was replaced by the bundled runtime because of what it cost: a first start
that needs the network (and a proxy, a firewall or a policy that allows PowerShell), about 1.5 thousand lines of
PowerShell and 88 tests that exist to make provisioning safe, a pin that had to be moved by hand with every Temurin
release and refused a PC's own newer build, and a Java on the PC that the simulator did not choose. The cost of the
bundled runtime is size: the zip is 35.6 MiB larger (44.4 to 80.0 MiB, measured; `distribution.md`). The user's installed
JDKs are irrelevant to the simulator. Two Javas are still involved: the simulator (window and worker) runs on the
bundled runtime, Java 25 (Foreign Function API final); importing a source project runs the project's own Gradle build
on a JDK 17 to 21 of the user's, found by `JdkDiscovery` (section "the importer's JDK" below).

**What is in the runtime.** `java.se` (every standard module, so a team library that needs one still runs) and
`jdk.unsupported` (`sun.misc.Unsafe`: LWJGL, the SDK), `jdk.management`, `jdk.crypto.ec`, `jdk.zipfs`, `jdk.net`,
`jdk.httpserver` (a robot program's own web server, such as Panels'), `jdk.charsets`: 51.3 MB unpacked in 200 files,
35.8 MB zipped, against 362 MB for the whole JDK. Linked with `--strip-debug --no-header-files --no-man-pages --compress
zip-6`. Left out: `jdk.localedata` (+10 MB; the worker runs with the en-US locale), the compiler, `jdk.jfr`, the
debugging and monitoring tools. The floor that `jdeps` finds over the window's, the worker's and Hornet's jars
(`java.base java.desktop java.instrument java.management java.net.http java.sql jdk.management jdk.unsupported`) is
47.6 MB / 32.4 MB zipped, so `java.se` and the extras cost 3.7 MB for safety; the whole JDK as a runtime
(`ALL-MODULE-PATH`, the fallback) would be 92.2 MB / 70.6 MB. The self test and the window start on the image, and the
real-host Hornet tests run on it (see `distribution.md`).

**The JDK it is made from.** The build's own toolchain, so that the runtime is the Java the code was compiled and tested
on; `-Pftcsim.runtimeJdk=<JDK home>` names another JDK of the same major version, for example an Eclipse Temurin 25 for a
release. The toolchain JDK of a development PC may be another vendor's (the measured PC has Oracle's 25.0.1, whose
licence is not the GPL with the Classpath Exception): the notices read the vendor and build from the JDK's `release`
file, and a release should be built from a Temurin JDK. The runtime's own licence texts are in `runtime\legal`.

**The hand-off to the application.** `FTC-Sim.cmd` is a small batch file with no PowerShell: it checks that `runtime`,
`lib\app.jar` and `runtime-options` are beside it (else exit 79, "extract the zip"), then runs `runtime\bin\java.exe`
with the Java argument file `runtime-options` (native access, Unsafe: the file is also a resource of `app.jar`, which the
self test checks against the running JVM), `-XX:ErrorFile` into `%LOCALAPPDATA%\FTC-Sim\logs`, and `-jar lib\app.jar`,
waits, and returns the application's exit code. `app.jar` carries the class path of the window in its manifest; a
`lib\*` wildcard would also put the worker's and the robot world's jars (which have an `android` package of their own)
on the window's class path, in no defined order. The application writes what it prints to standard output and error to
`logs\app-<time>.log` itself (`AppLog`, and the console shows it too), so no batch timestamp is needed. Behaviour that
changed with the PowerShell: the console of a double-click now stays open while FTC-Sim runs (a batch file cannot both
wait for the program, to show a failure, and close its own console), and a failure of the program still keeps it open;
the Java crash report is its own file, `logs\hs_err_pid<pid>.log`, not part of the application's log.

**The importer's JDK.** Importing a source project runs the project's own Gradle build, which a 2024 project cannot run on
Java 25. `JdkDiscovery` finds a JDK 17 to 21 at the places listed in `jdk-patterns.txt` (a resource of `app`: Program
Files, Adoptium, Android Studio's `jbr`, `.gradle\jdks` and so on) and `JAVA_HOME`; the discovery, the pin-free
choice and its messages are unchanged by the bundled runtime and are not the simulator's concern.

**Evidence.** `DistributionLayoutTest` (what the folder and the zip hold, the runtime's modules and what it leaves out),
`ThirdPartyNoticesTest`, `AppLogTest`, `SelfTestTest` and `DistributionEndToEndTest`, which starts the real distribution
through `FTC-Sim.cmd` from a folder with spaces and umlauts, with no Java on the PATH: the self test on the bundled
runtime, the hand-off (options, arguments, exit code, an exception, a real JVM crash whose report lands in the logs
folder), and a folder without its runtime. The real window was started on the image with `--no-focus --exit-after`.

**Licence notices.** The distribution carries `THIRD-PARTY-NOTICES.txt` and the full licence texts, generated by
`:app:thirdPartyNotices` from the POMs of the jars that fill `lib`; a jar whose licence the build cannot determine
fails the build with its name. The runtime has its own section, written from the JDK's release file, that points at
`runtime\legal`. The product owner still has to choose a licence for FTC-Sim's own code: the repository has none, and none
was invented.

**Open.** Not run on a clean machine or under a real application-control policy; whether Windows SmartScreen or an
antivirus scanner treats an unpacked `java.exe` from a downloaded zip differently from the earlier extraction into the
user's cache is not known (it was not testable). A folder name with characters outside the Windows ANSI code page makes
the Java launcher fail to open `app.jar`, as it did before. A release is built from a Temurin JDK by choice of the
owner, not by the build.

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
and to practise driving. Repeatability was promised before, and it cost more than it was worth: the simulator waited in
real time for every library thread before time could move, rewrote every source of randomness in the robot's JDK, kept a
hash of the physics state pinned across JVM flags, and flagged any run it could not vouch for. Each of those was
machinery, and some of it (Panels' start-up thread against the OpMode thread) was a source of waits and failures of its
own.

**What stays: lockstep virtual time.** The robot thread runs only when the simulator hands it the baton and gives it back
at its next hook (`SimulationKernel`, `RobotGate`, `VirtualClock`, `FixedRateWorld`; the rewrites of `TimeRules` and
`RewriteRules`). It stays because it is what makes the product work, not because it makes runs repeat: the run can be
paused, slowed and fast-forwarded without the robot code noticing (a sleep or a loop is simulated time, never real time);
a loop iteration costs simulated time rather than however long the machine took, so loop timing is steady; and the world,
the hubs and the devices have one writer at a time and need no locks. An OpMode therefore sees the same sleep durations
and the same readings however the caller slices time, and tests still assert that, on outcomes (a time, a pose within a
tolerance), not on hashes.

**What a run to run difference looks like.** A robot program that waits on a library thread (Hornet's Panels start-up
is one) spins its loop a different number of times each run, and each spin costs simulated time, so two runs start a few
microseconds apart. Most runs are identical; now and then a run differs, and an autonomous, which makes many discrete
decisions, can end on a different score (Hornet's far autonomous classified 9 artifacts on most runs and 6 on some).
Tests that play a whole autonomous therefore ask that it can reach its bar within three runs (`HornetAutoRunTest`)
instead of on every run; a broken robot reaches it in none.

**What was removed.**

- Waiting for foreign threads (`WorldThreads`; the quiet and settle waits before time moves, before a callback and before
  the baton is handed over; `KernelDiagnostics.worldWaits()`; the matching `KernelConfig` settings). A library's own
  thread now runs in real time beside the simulation, between the robot thread's turns or during one. What it does to the
  hubs it does safely (section 3), and it moves no simulated time.
- The reproducibility flags: the problems that said a run was "not reproducible", and the counting of changes made by
  threads the kernel does not schedule (`LynxBus.unscheduledMutations()`).
- Randomness virtualisation: the `Random`, `UUID`, `SecureRandom`, `ThreadLocalRandom` and `Collections.shuffle` rewrites
  (`RandomRules`, `RandomHooks`, `ThreadLocalSeeder`, `SimulatedSecureRandom`) and the kernel's seed. The robot world gets
  the JVM's own randomness. The field's own seed (`FieldSetup.seed`: reserve order, layout noise) stays, since a field
  is laid out from it.
- `StateDigest`, `PhysicsWorld.stateHash()`, the golden hashes and everything that compared hashes across slicings, JVMs
  and JIT settings, and the identity-hash order test.
- The rules that existed for repeatability in the simulation's own code: `StrictMath` is no longer required (existing
  uses stay), hash-ordered collections and unseeded randomness are allowed where they are harmless.

**What was kept, because the OpMode needs it to run, stop and unwind, not to repeat.** The monitor settle (a robot thread
entering or leaving a wait is briefly in a state the simulator cannot read); detection of a robot thread blocked in
something the simulator does not control (`RobotGate.State.EXTERNAL`) and the wait for it when the SDK interrupts it;
unwinding and abandoning a robot thread, and `System.exit` handling (section 10); the notification of a robot waiter by
another thread being held until the baton is granted, since the real monitor is the robot's park; `settle` after the host
starts or wakes the robot thread, so that a thread that was just created reaches the simulator; and the bus's lock. The
problems the kernel still reports are about the robot thread (it would not end, it came back from an uncontrolled wait,
a second thread looks like it) and say so, without claiming anything about repeatability.

**What can now differ between two runs.** The interleaving of a library's own threads (Panels' start-up thread and its
ticker, a `Timer`) with the OpMode, which can move an OpMode's hub transmission across a hub tick; the start-up latency
of a thread; random numbers; the iteration order of identity-hashed sets. The Hornet tests assert outcomes (scores, poses
within a tolerance), not worlds. Recording and replaying a session is not a feature (it was built and removed as not
needed).

## 10. The robot world's contact with the machine

What a robot program can reach outside its class loader, and what the simulator answers. The rule behind it all: the
robot's clock is the simulated clock, and nothing it reaches touches the machine's network. Where the simulator cannot
answer for the robot thread (it would not end, it came back from a wait the simulator does not control), the run says so,
as a problem (`KernelDiagnostics.problems()`, `SimulationSession.problems()`). Randomness and the timed waits of library
code are the machine's own (section 9). This section replaces the open items of section 2.

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

**Time.** Every robot-visible source of the wall clock comes from the simulated clock (`KernelConfig.epochMillis`,
2026-01-01T00:00:00Z by default). The rewrites are table-driven (`TimeRules`, whose tests scan the JDK so that a new
`now()` is noticed): `new Date()`, `Calendar.getInstance`, `new GregorianCalendar`, `Clock.system*` and `tick*`,
`Instant.now()` and the `now()` and `now(ZoneId)` of every class of `java.time`. Direct calls, method references,
lambdas and the `super()` of a subclass of `Date` are covered. Only the instant is virtualised: the time zone and the
locale stay the machine's. *Not* virtualised: randomness (`Random`, `Math.random`, `UUID.randomUUID`, `SecureRandom`,
`ThreadLocalRandom` are the JVM's own), the timed waits that library code makes through the JDK (`Timer`,
`Semaphore.tryAcquire`, `BlockingQueue.poll`, `Condition.await`, `LockSupport.parkNanos`, `Thread.join`),
`Runtime.availableProcessors`, the machine's zone and locale, file timestamps.

**Foreign threads.** A thread of the robot world other than the robot thread, such as Panels' starter and ticker or a
library's timer, runs in real time and the kernel does not schedule it or wait for it. Its hub traffic goes through
`transmitUnscheduled` (section 3): under the bus's lock, never moving simulated time, queries as the hubs stand. Its
`notify` of a monitor the robot thread waits on is held until the simulator grants the baton, because the robot's wait
is a real one. What the kernel still does about the robot thread itself is about running, stopping and unwinding it, and
not about repeatability: it waits for the robot thread to park, end or block when the simulator acts on it (`settle`, after
a host call that starts or wakes it); it gives a robot thread that is entering or leaving a monitor wait the time to be
seen in it; it follows a lock chain, so that a robot thread that waits for a lock a *running* thread holds is waited for
and one blocked on something the simulator does not control (`EXTERNAL`) is left, time going on without it, and reported
as a problem when it comes back, while a thread the SDK interrupts is waited for when it reacts. A robot thread that the SDK
starts inside a Stop (the next OpMode's) is not waited for before time moves: its start-up in real time counts as simulated
time, a fraction of a millisecond on a quiet machine and tens on a loaded one, which is why the runaway tests allow 250 ms
of slack. Panels' start-up thread
filling its configurables list while the OpMode thread walks the same list was the failure that the hold-off once
cured; it is a race in Panels' own code that the simulator no longer hides. If a Hornet run ever shows it again, the
cure is to run that thread on the simulated clock, not to bring the waiting back.

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

- **Replay and recording of runs.** Built, then removed: nothing in the product needed it. A new run is a new session.
- **Bit-exact repeatability and golden hashes.** Built (a state hash of the physics world pinned across JVM flags and
  slicings of time, a hold-off for every library thread, virtualised randomness), then removed by the owner: accuracy
  and repeatability were never the goal, and the machinery cost waits, flaky corners and tests of its own. Lockstep virtual
  time stayed (section 9).
- **TOML and YAML robot files.** One format, JSON (`ftcsim.json`, read and written whole by `ProjectMappingsImpl`): no second
  parser to keep in step. A `ftcsim.toml` that an earlier tool left in the Hornet checkout was replaced by a copy of the JSON file.
- **Air drag.** The owner judged it excessive for what the shot tests need. The launcher is fitted by its angle and
  efficiency to the shooter table of the robot (`robots/hornet-profile.md`), and a far shot scores by banking off the back
  of the goal, as real ones do (`fields/decode.md`, "Friction, bounce and bank shots").
- **Mechanisms that push on the chassis** (section 8), **foul and ranking-point rules, an opposing robot or a second robot**,
  and **features for other seasons**: the aim is to test a team's code and to practise driving, not to referee or to
  design a robot.
- **A browser dashboard for Panels.** Its server asks to listen and gets a simulated listener that nobody can connect to
  (section 10); the program runs, the page does not exist.
- **A Rust core or sidecar** (section 1) and **platforms other than Windows**.
