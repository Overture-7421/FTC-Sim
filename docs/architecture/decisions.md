# Architecture decisions

Why FTC-Sim is built the way it is: read it before you argue with a choice; the owner's decisions are marked and need the owner to reverse them.

Windows only. Every number was measured on one development desktop unless stated; none has been measured on a student laptop.
"Gate" in a heading is a feasibility prototype with a pass criterion, run before the choice was built on. A "conditional pass" needed hardware the project did not have; the condition is under **Open**.

## 1. The stack: JVM end to end, one native physics engine

- **Decision.** Java for the simulation, hardware emulation and SDK adapters; a small Kotlin layer for the Material 3 UI; Jolt (jolt-jni) for physics. No Rust.
- **Why.** The robot program is Java and runs the real FTC SDK, so the hard work (virtual time, bytecode rewriting, the SDK's Android assumptions) is on the JVM whatever the rest is written in. A Rust core would add a language and a per-call bridge to the device models and remove none of that work.
- **Consequence.** Native code only where it pays: the physics engine.

## 2. Two class loaders and virtual time

- **Decision.** The host (kernel, physics, hardware emulation, UI) and the robot world (team program, FTC SDK, clean-room `android.*` shim, `sdk-common`) are separate class loaders; only `dev.ftcsim.contracts.*` is shared. The robot world's clock, sleeps, waits, monitors and loop back edges are rewritten at load time to call the kernel, which moves simulated time only on the simulator thread and runs the robot thread between moves. The real SDK's `OpModeManagerImpl` runs iterative and linear OpModes, busy waits, and a stop that interrupts a sleep.
- **What time costs.** Robot code moves simulated time only through hooks: a clock read 200 ns, a yield 1 µs, hub traffic what its wire takes, each backward jump `SimulationSession.LOOP_NANOS` = 100 ns (about one trivial loop iteration on a Control Hub).
- **Why 100 ns.** A loop that only waits (`while (opModeInInit()) initialize_loop();`, a student's `while (!gamepad1.a) {}`) reads no clock and touches no hardware, so only its jumps let time pass. At 1 ns each, Hornet's Init ran at 0.38× real time (64 % of the robot thread's samples were in the hook). At 100 ns it runs at about 8×, as fast as the rest of the world allows, and the Hornet autos score the same. `SessionSpeedTest` fails if the cost is made too small again.
- **Consequence.** Computation costs simulated time too (1000 iterations = 100 µs), closer to a Control Hub than free computation.
- **Open.** Timed waits that library code makes through the JDK run in real time (section 10). Section 9 says why lockstep stays although repeatable runs are not promised.

## 3. Hardware is emulated at the wire

- **Decision.** The SDK's own `LynxModule`, motor controllers and I2C drivers run unchanged. Only the USB device is replaced: it hands the SDK's serialised RHSP datagrams to a pure-Java emulation of REV hub firmware. A new I2C device is a model on a hub bus; the real SDK driver then works as on a robot, on SDK 11 and 12 alike (their hub code is identical source).
- **Threads.** The bus has one lock; the scheduled threads (simulator, robot) take turns at it. A thread the kernel does not schedule (a library's timer; Panels reads the battery voltage from one) uses a separate entry under the same lock that never moves simulated time: queries are answered as the hubs stand and leave no trace, and anything that would change a hub is applied.
- **Consequence.** Nothing is flagged for such threads; what they change depends on when the OS runs them (section 9).

## 4. Physics: Jolt (Gate 3, conditional pass)

- **Decision.** Jolt through jolt-jni 7.0.0, single precision, one thread, 240 Hz fixed step. The code refuses a native build that is not Jolt's cross-platform-deterministic variant or that is double precision; it does not check the DLL's hash at run time. The DLL is unpacked into a folder named after its SHA-256 so two builds never share a file; a test asserts the hash of the one that ships.
- **Why.** A step costs about 0.15 ms against a 4.17 ms budget; the DLL imports only `KERNEL32`.
- **Consequence.** No run-time hash check and no flush-to-zero probe: both served the repeatability promise that section 9 drops.
- **Open.** No static drivetrain friction; the chassis parameters are estimates, not fitted to a robot.

## 5. UI: Compose Material 3 over an offscreen 3D viewport (Gate 4, conditional pass)

- **Decision.** LWJGL OpenGL renders the 3D scene offscreen into a framebuffer, reads it back through pixel-buffer objects and shows it as a bitmap inside Compose, so every Material 3 element overlays it.
- **Why.** `viewport-measurements.md`: frame interval p99 7.0 ms at 144 Hz and 17.2 ms at 60 Hz, no stall above about 55 ms, 58 to 84 % of one core, 0.9 to 1.4 ms per frame for readback and copy.
- **Rejected.** A native canvas in Compose (overlays hidden, native crash with window layers); a docked window (blanks on resize, cannot be overlaid); sharing Skia's GL context (the driver refuses).
- **Open.** Not measured on a 4-core integrated-GPU laptop or a display above 60 Hz. Presentation latency is at least two frames (29 to 38 ms at 60 Hz).

## 6. The simulation runs in a worker process

- **Decision.** The UI holds a `SimulatorLink` (commands in, snapshots and status out). A worker process runs the simulation in real time on its own thread, the only place wall-clock time touches it.
- **Why.** A crashing or runaway robot program, or a native fault in the physics engine, must not take the UI down, and a new worker is the cleanest reset. Details: [link.md](link.md); where the application starts it: [app.md](app.md).
- **Open.** Loopback TCP with a random per-launch token keeps strangers out of a launch; it is not authentication against another program of the same user. A session that does not stop within 20 s ends the worker.

## 7. The runtime comes with the program (Gate 5, conditional pass; reversed in phase 6)

- **Decision.** A bundled runtime. `:app:dist` links a Java 25 runtime with `jlink` into the distribution's `runtime` folder, and `FTC-Sim.cmd` (a batch file, no PowerShell) starts `runtime\bin\java.exe`. The user's installed JDKs are irrelevant to the simulator. Layout, launcher and licence notices: [distribution.md](distribution.md).
- **Why it replaced download-first.** Download-first (a pinned Temurin 25.0.5+8, checksum verified, fetched on first start by PowerShell that also searched for JDKs) cost: a first start that needs the network and a policy that allows PowerShell; about 1.5 thousand lines of PowerShell and 88 tests that existed to make provisioning safe; a hand-moved pin that refused a PC's own newer build; and a Java the simulator did not choose.
- **Trade-off.** Size: the zip grows by 35.6 MiB (44.4 to 80.0 MiB, measured).
- **Contents.** `java.se` (every standard module, so a team library that needs one still runs) plus modules it does not name (`sun.misc.Unsafe` for LWJGL and the SDK, a robot program's web server such as Panels'): 51.3 MB unpacked in 200 files, 35.8 MB zipped, against 362 MB for the whole JDK. The `jdeps` floor over the window's, the worker's and Hornet's jars is 47.6 MB / 32.4 MB zipped; `java.se` costs 3.7 MB for safety, since a missing module is a `NoClassDefFoundError` in team code. Left out: `jdk.localedata` (+10 MB; the worker runs with the en-US locale), the compiler, `jdk.jfr`, debugging and monitoring tools.
- **Source JDK.** The build's Gradle toolchain, so the runtime is the Java the code was compiled and tested on (`-Pftcsim.runtimeJdk=<JDK home>` names another of the same major version). The measured PC's toolchain JDK is Oracle's 25.0.1, whose licence is not the GPL with the Classpath Exception, so **a release is built from an Eclipse Temurin 25 by the owner's choice**, not by the build.
- **Importer exception.** Importing a source project runs the project's own Gradle build, which a 2024 project cannot run on Java 25, so `JdkDiscovery` finds the user's JDK 17 to 21 ([app.md](app.md)).
- **Open.** Not run on a clean machine or under a real application-control policy; whether SmartScreen or an antivirus treats an unpacked `java.exe` from a downloaded zip differently is not known. A folder name outside the Windows ANSI code page makes the Java launcher fail to open `app.jar`. The owner still has to choose a licence for FTC-Sim's own code: the repository has none.

## 8. Game pieces: zones on plants, owned exactly once

- **Decision.** Mechanisms never push on the chassis. A motor is a plant on a hub port. Intakes, storages and launchers are zones whose activation is a condition on named motors' shaft speeds, never on the robot program.
- **Ownership.** A piece is a free body in the physics world, or carried by a storage (drawn from it, found by sensor rays analytically), or in the field's reserve: never two.
- **Intake size.** An intake takes only the piece sizes it is built for (an optional diameter range in the robot's design), so a robot built for one game's pieces does not pick up another's by accident. The range is physical: no game or piece name appears in a spec.
- **Rolling resistance.** Jolt alone never stops a rolling ball: it reuses contact points until bodies move 1 mm, so a ball slower than 1 mm per step keeps a stale contact that drives the roll, and damping settles at about 0.24 m/s for ever. A piece whose spec sets a rolling resistance gets fresh contacts every step and loses spin at the rate the coefficient implies, so it stops in finite time and sleeps. One mechanism in `sim-physics` on `PieceSpec`, used by both fields. The coefficient (0.06) is an estimate, not a measurement.

## 9. Repeatability is not promised

- **Decision (product owner).** Two runs of the same program, field, seed and inputs are not promised to give the same world, bit for bit or at all. The goal is to test a team's code and practise driving, not accuracy or repeatability.
- **Why.** The promise cost more than it was worth: waiting in real time for every library thread before time could move, rewriting every source of randomness, a state hash pinned across JVM flags, and flags on runs the simulator could not vouch for. Some of it (Panels' start-up thread against the OpMode thread) caused waits and failures of its own.
- **Gone:** waiting for foreign threads, reproducibility flags, randomness virtualisation, state hashes, recording and replay. The field's own seed (`FieldSetup.seed`: reserve order, layout noise) stays, since a field is laid out from it.

**What stays: lockstep virtual time** (`SimulationKernel`, `RobotGate`, `VirtualClock`, `FixedRateWorld`; the `TimeRules` and `RewriteRules` rewrites). The robot thread runs only when the simulator hands it the baton and gives it back at its next hook. It stays because the product needs it, not because it makes runs repeat:
- the run can be paused, slowed and fast-forwarded without the robot code noticing;
- a loop iteration costs simulated time, not however long the machine took, so loop timing is steady;
- the world, hubs and devices have one writer at a time and need no locks.

An OpMode sees the same sleep durations and readings however the caller slices time. Tests assert outcomes (a time, a pose within a tolerance), not hashes.

**Kept because the OpMode must run, stop and unwind:** the monitor settle (a robot thread entering or leaving a wait is briefly in a state the simulator cannot read); detection of a robot thread blocked in something the simulator does not control (`RobotGate.State.EXTERNAL`); unwinding and abandoning a robot thread and `System.exit` handling (section 10); holding a robot waiter's notification from another thread until the baton is granted; `settle` after the host starts or wakes the robot thread; the bus's lock. The problems the kernel reports concern the robot thread (it would not end, it came back from an uncontrolled wait), not repeatability.

**What can differ between two runs:** the interleaving of a library's threads (Panels' start-up thread and ticker, a `Timer`) with the OpMode, which can move a hub transmission across a hub tick; thread start-up latency; random numbers; iteration order of identity-hashed sets.
- A robot program that waits on a library thread (Hornet's Panels start-up) spins its loop a different number of times, each spin costs simulated time, so two runs start a few microseconds apart.
- Most runs are identical; now and then an autonomous, which makes many discrete decisions, ends on a different score (Hornet's far autonomous classified 9 artifacts on most runs and 6 on some).
- So tests that play a whole autonomous ask that it reaches its bar within three runs (`HornetAutoRunTest`); a broken robot reaches it in none.

## 10. The robot world's contact with the machine

What a robot program can reach outside its class loader, and what the simulator answers. The rule: the robot's clock is the simulated clock, and nothing it reaches touches the machine's network. Where the simulator cannot answer for the robot thread, the run reports a problem (`KernelDiagnostics.problems()`, `SimulationSession.problems()`). Randomness and the timed waits of library code are the machine's own (section 9).

**Network policy.** The robot world does not listen or connect on the real network, with one exception: a listening socket on a non-loopback address makes Windows ask a student about its firewall, and traffic makes a run depend on the outside.
- Every JDK way to bind, listen, connect or send is rewritten to `NetworkHooks` (table: `NetworkRules`; a test fails when a JDK member that reaches the network is missing).
- Listening (`ServerSocket`, `ServerSocketChannel`) gets a *simulated listener*: it holds the port in a private network of the robot world, binds nothing on the machine, `accept` waits until closed, and no client can connect. Each is recorded (`NetworkHooks.simulatedRequests()`).
- Everything else is declined, fails the way a closed port fails, and is recorded (`declinedRequests()`).
- **The exception, approved by the owner: the Panels ports.** Panels (the dashboard library teams use) serves its page on 8001 and its websocket on 8002, and a browser has to reach them. A `ServerSocket` that binds one of those two ports (`KernelConfig.loopbackPorts`, set by `SimulationSession`) gets a *real* listener on `127.0.0.1`: the wildcard address the library asks for is replaced by the loopback address and never bound (Windows asks only about non-loopback listeners, and nothing beyond this machine can connect), the reuse flag is not passed on (on Windows it would let a second FTC-Sim share the port), and accepted sockets are the JDK's, served to the library's own threads in real time. A port another program holds leaves a simulated listener and a problem of the session (`NetworkHooks.realListenerFailures()`); real ones are in `realRequests()`. `ServerSocketChannel`, a bind to another address and every other port stay simulated. `SessionStatus.dashboardUrl` carries `http://127.0.0.1:8001/` while 8001 is real and the window offers it as a button.
- Hornet's Panels also listens on 5800, 5801, 5805, 5807 (the Limelight proxy): simulated. Binding the wildcard address stays out.
- When a session closes its listeners close, the real ones too and the ports are free at once. A thread that keeps accepting on its closed socket waits for good instead of failing again and again (Panels' proxy otherwise logged 2.8 million lines in a few seconds).

**Lifecycle hooks.** `ControllerLifecycle` does what the Robot Controller app does around the robot's life, through the SDK's own `AnnotatedHooksClassFilter`: it calls the libraries' `@OnCreate` hooks, gives them the event loop (`@OnCreateEventLoop`) and an in-process web handler manager (`@WebHandlerRegistrar`), and calls `@OnDestroy` at the end. The SDK logs and swallows what a hook throws, so a failed hook, like a thread that dies of an unhandled exception, becomes a warning on the Driver Station.

**The APK index.** Libraries ask the app what it contains (the SDK's `ClassManager`, Panels' `ClassFinder`, Sloth's `SinisterImpl`); on a device that is the APK's `classes.dex`.
- The simulator writes a real zip whose `classes.dex` is only a class-name index (`ApplicationPackage`, `DexIndex`), read through the shim's `dalvik.system.DexFile` (and `dalvik.system.PathClassLoader`, which loads classes listed in the index from the robot world's class loader so bytecode hooks stay in place); the classes load from the program.
- A class that cannot be linked here (for want of Android's user interface) would log a hundred lines of stack (8,000 for Hornet). SDK and library classes are therefore one line with the count and the commonest reasons; the team's own stay in full (`ScanLog`, `RobotProgram.teamClasspath()`).

**Android ART reflection.** Two places where Android's ART runtime is more permissive than HotSpot are emulated for robot-world classes by rewriting `Field.set*` and `Method.invoke` to `ReflectHooks` (`ReflectRules`):
- Writing a `static final` field after `setAccessible(true)` succeeds on ART (`art/runtime/native/java_lang_reflect_Field.cc`), whereas HotSpot throws `IllegalAccessException`. Libraries such as Sloth/Sinister rely on this to replace SDK singletons like `RegisteredOpModes.TAG` and `RegisteredOpModes$InstanceHolder.theInstance`. `ReflectHooks` performs the write through `sun.misc.Unsafe` only for classes loaded inside the robot world, never for JDK or host classes.
- Invoking a `public` method declared on a non-public class succeeds on ART (`art/runtime/reflection.cc` `VerifyAccess`), whereas HotSpot rejects cross-package calls on package-private classes such as Kotlin `private object` hook call sites. `ReflectHooks` matches ART for robot-world classes while keeping standard Java access checks for every other call.

**Runaway OpModes.** An OpMode that ignores Stop gets the SDK's own timeouts in simulated time (`RunawayGuard`): the stuck-code timeout, then 100 ms during which the hubs' bus lock throws at whatever loop touches a hub. Pausing, slowing or fast-forwarding can neither trip nor delay them. After that:
- `RobotUnwind` (an `Error`) is thrown at the robot thread's next hook (clock read, sleep, wait, yield, loop back edge); threads blocked in the JDK are interrupted; a thread that swallows the error is asked again every 250 ms, four times.
- One that still does not end is abandoned: parked for good at its next hook, recorded in `KernelDiagnostics.abandonedThreads()` and as a problem, and the SDK's wait for it is released so the session can run another OpMode.

**`System.exit`.** `System.exit`, `Runtime.exit` and `Runtime.halt` from robot code never end the JVM. The call throws `RobotExit` (a `RobotUnwind`), the request is recorded with its status and thread, the controller shows an error and stops the OpMode, and the program's shutdown hooks are remembered and never run. Both this and an abandoned thread are problems of the session.

**Time.** Every robot-visible source of the wall clock comes from the simulated clock (`KernelConfig.epochMillis`, 2026-01-01T00:00:00Z by default) by table-driven rewrites (`TimeRules`; its tests scan the JDK so a new `now()` is noticed): `Date`, `Calendar.getInstance`, `GregorianCalendar`, `Clock.system*` and `tick*`, and the `now()` of every `java.time` class. Only the instant is virtualised. **Not virtualised:**
- randomness (`Random`, `Math.random`, `UUID.randomUUID`, `SecureRandom`, `ThreadLocalRandom`);
- the timed waits library code makes through the JDK (`Timer`, `Semaphore.tryAcquire`, `BlockingQueue.poll`, `Condition.await`, `LockSupport.parkNanos`, `Thread.join`);
- `Runtime.availableProcessors`, the machine's zone and locale, file timestamps.

**Foreign threads.** A robot-world thread other than the robot thread (Panels' starter and ticker, a library's timer) runs in real time; the kernel neither schedules nor waits for it. Its hub traffic goes through `transmitUnscheduled` (section 3); its `notify` of a monitor the robot thread waits on is held until the simulator grants the baton. The kernel still concerns itself with the robot thread's running, stopping and unwinding:
- it waits for the robot thread to park, end or block when the simulator acts on it (`settle`);
- it follows a lock chain: a thread waiting for a lock a *running* thread holds is waited for; one blocked on something the simulator does not control (`EXTERNAL`) is left, time goes on without it, and it is reported as a problem when it comes back.
- A robot thread the SDK starts inside a Stop (the next OpMode's) is not waited for: its real start-up counts as simulated time, a fraction of a millisecond on a quiet machine and tens on a loaded one, which is why the runaway tests allow 250 ms of slack.
- Panels' start-up thread filling its configurables list while the OpMode thread walks it is a race in Panels' own code, no longer hidden. If a Hornet run shows it again, run that thread on the simulated clock; do not bring the waiting back.

**Host shutdown.** Closing a session closes the world's listeners, interrupts every thread whose context class loader is the robot world's, and waits one second. A thread that ignores the interrupt cannot be killed: it keeps running and is reported as a problem of the closed session (Hornet leaves three: `Timer-0`, the Limelight proxy, Panels' ticker). The production worker ends with the session, so only a host that runs several sessions in one JVM carries them.

**Open.** Library timers and timed waits on the simulated clock; ending threads that swallow interrupts (a tracked `Timer` the host cancels); the zone and locale of the simulated machine; `Runtime.availableProcessors`.

## 11. What was left out, and why

Each was considered and decided against; adding one back is the product owner's call.

- **Replay and recording of runs.** Nothing in the product needs it; a new run is a new session.
- **Bit-exact repeatability and golden hashes.** Never the goal; the machinery cost waits, flaky corners and tests of its own (section 9).
- **Robot description files of any kind** (`ftcsim.json`, TOML, YAML) **and a robot editor.** The robots are built into the simulator (section 12); nothing is read from a team's project.
- **Air drag.** Excessive for what the shot tests need. The launcher is fitted by angle and efficiency to the robot's shooter table (`robots/hornet-fit.md`), and a far shot scores by banking off the back of the goal, as real ones do (`fields/decode.md`, "Friction, bounce and bank shots").
- **Mechanisms that push on the chassis** (section 8), **foul and ranking-point rules, an opposing or second robot**, **features for other seasons.** The aim is to test code and practise driving, not to referee or design a robot.
- **A Rust core or sidecar** (section 1) and **platforms other than Windows.**

## 12. The robots are built into the simulator

- **Decision (product owner).** FTC-Sim runs a team's unmodified robot project on **two built-in robots**, Hornet's (on DECODE and the bare floor) and BIOBUZZ's, whose numbers are literal data in `RobotProfiles` (`simulator`). The simulator is the team's own, so a team supplies nothing: no `ftcsim.json`, no robot screen, no sensor settings, and nothing about the robot is read from the project (the importer no longer treats such a file specially). The robot is chosen by the field (`biobuzz` gets BIOBUZZ's, every other field Hornet's). A name the team's code asks for that the robot lacks is an unmapped device, reported in the session's problems.
- **Why.** The only teams that use the simulator are the ones whose robots are built in; a mapping window and a file format were the largest part of the window for no one's use, and a fixed robot makes a session's request a field, an alliance and a pose.
- **Consequences.** Refitting a robot means editing `RobotProfiles` and the numbers its test pins ([development](../development.md)). Hardware names are the profile's: the colour sensors are bound by declared name (`upSensor`, on Hornet, `upperSensor` and `lowerSensor` on BIOBUZZ); the Pinpoint, IMU and OctoQuad are bound by class under any name. An old `ftcsim.json`, `%LOCALAPPDATA%\FTC-Sim\profiles` and the settings keys `scanOpMode` and `robotSkipped` are neither read nor written.
