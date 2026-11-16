# The distribution and how FTC-Sim starts

A driver unzips `FTC-Sim.zip` anywhere, double-clicks `FTC-Sim.cmd`, and FTC-Sim opens, on a Windows 10 or 11 x64 PC
that has no Java, no network and no PowerShell policy to satisfy. `:app:dist` builds the folder and the zip: the
application's jars, a Java 25 runtime linked with `jlink` (`runtime\`), and `FTC-Sim.cmd`, a small batch file that starts
the application on that runtime. Decisions and measurements are in `decisions.md`, section 7.

## What is in the folder

```
FTC-Sim\
  FTC-Sim.cmd            the double-click entry point: starts runtime\bin\java.exe, hands back the exit code
  runtime-options        the Java options the application needs, a Java argument file (also a resource of app.jar)
  runtime\               the Java 25 runtime (bin, conf, lib, legal, release): 200 files, 51.3 MB
  THIRD-PARTY-NOTICES.txt  every jar of lib: its library, licence and project; the Java runtime and where its licences are
  licenses\              the 8 licence texts that file points at
  lib\
    app.jar              the application; its manifest names Main-Class and the jars of the window (Class-Path)
    ftcsim-classpath.properties   worker= and robot= lines, bare jar names relative to the file
    *.jar                78 more: the window, Compose, Skia, LWJGL with natives-windows, Kotlin, the worker,
                         the simulator and its parts, jolt-jni Windows64, robot-runtime, android-shim, sdk-common ...
```

291 files, 98.3 MB unpacked, zip 80.0 MiB (before the bundled runtime: 104 files, 49.2 MB, 44.4 MiB; the runtime is the
difference, the jars hardly compress). No source, no tests, no Gradle cache, no JDK tools. Every jar is in `lib` once,
however many of the application's, the worker's and the robot world's class paths it is on (`sim-contracts.jar` is on all
three); two different files with the same name fail the build. The longest path inside the folder is 64 characters, so an
install folder up to about 190 characters deep is safe for tools that stop at Windows' 260-character path limit.

## The runtime

`runtime\` is a `jlink` image of the JDK that the project is compiled for: the toolchain of `ftcsim.java-conventions`
(`jdk = "25"` in `gradle\libs.versions.toml`), found by Gradle's toolchain service, and its own `jlink.exe`, so the
runtime is the Java the code was built and tested on. `-Pftcsim.runtimeJdk=<JDK home>` names another JDK of the same
major version (for a release, an Eclipse Temurin 25; the task refuses a JDK of another major version, or a JRE). Nothing
is downloaded: if Gradle has to provision the toolchain it does so for the build, as it always did.

The modules (`runtimeModules` in `app\runtime.gradle.kts`): `java.se`, which is every standard module, so that a team
library that needs `java.sql`, `java.naming`, `java.rmi` or `java.net.http` still runs, and the ones `java.se` does not
name: `jdk.unsupported` (`sun.misc.Unsafe`, which LWJGL and the FTC SDK use), `jdk.management`, `jdk.crypto.ec`,
`jdk.zipfs`, `jdk.net`, `jdk.httpserver` (a robot program's web server, such as Panels', uses `com.sun.net.httpserver`) and
`jdk.charsets`. The options: `--strip-debug --no-header-files --no-man-pages --compress zip-6`. There is no `--module-path`:
jlink links the running image of its own JDK, which every JDK of Java 24 or later can do, so a JDK with or without `jmods`
gives the same runtime. It also brings `msvcp140.dll`, `vcruntime140.dll` and the Universal CRT forwarders of the JDK, so
the PC needs no Visual C++ runtime for Java.

**The spike** (Oracle JDK 25.0.1 on the development PC, the toolchain's JDK; unpacked, and zipped with .NET's optimal
deflate):

| Runtime | Unpacked | Zipped | Files |
|---|---|---|---|
| the whole JDK (for scale) | 362 MB | | |
| floor: what `jdeps` finds over the window's, the worker's and Hornet's jars (`java.base java.desktop java.instrument java.management java.net.http java.sql jdk.management jdk.unsupported`) | 47.6 MB | 32.4 MB | 158 |
| **`java.se` + the 7 extras above (the runtime)** | **51.3 MB** | **35.8 MB** | 200 |
| the same + `jdk.localedata` | 61.4 MB | 45.2 MB | 204 |
| `ALL-MODULE-PATH` (the fallback: every module of the JDK) | 92.2 MB | 70.6 MB | 344 |
| the runtime from the Eclipse Temurin 25.0.5 (no `jmods` in that JDK; linked from its image) | 53.2 MB | 37.1 MB | |

The floor is 3.7 MB smaller than the runtime; `java.se` stays because a team library that needs a standard module the
jdeps list misses would fail with a `NoClassDefFoundError` in team code, which is the one thing a smaller image buys too
little to risk. `jdk.localedata` is left out: 10 MB for the locale data of other languages, while the worker runs with
the en-US locale. The fallback, if an image ever proves problematic, is `ALL-MODULE-PATH` in `runtimeModules`: 41 MB bigger
(zip 80.0 to about 115 MiB).

**Validated on the image.** The window started on the floor image and on the runtime with `--no-focus --exit-after`
(exit 0); `--self-test` passes on it (the worker, Jolt and the window's classes); `DistributionEndToEndTest` runs the
self test, the hand-off and a Java crash through `FTC-Sim.cmd` on the distribution's own runtime; and the real-host
Hornet tests (`RealHostHornetTest`, `HornetPackageTest`) run with the runtime as the JVM of the test and so of the worker
(`-Pftcsim.runtime=app\build\dist\FTC-Sim\runtime`, see "Verification" below). No module was missing.

## Building it

`.\gradlew.bat :app:dist` (VS Code: `ftc-sim: build distribution`) writes `app\build\dist\FTC-Sim\` and
`app\build\dist\FTC-Sim.zip`. The pieces, in `app\build.gradle.kts` and the two script plugins it applies:

- `runtimeImage` (`LinkRuntime`, `app\runtime.gradle.kts`) runs the jlink of the toolchain JDK into `build\dist-parts\runtime`.
  Its inputs are the JDK's `lib\modules` and `release` files, the module list and the major version, so it is up to date
  until the JDK or the list changes; it fails when the JDK is not the major version the code is compiled for.
- `writeClasspath` and `writeDistClasspath` are one task type, `WriteClasspathFile`: the first writes absolute paths into
  the build folders for `:app:run` and the tests, the second the bare names for the folder. The format is the one
  `AppClasspath` reads, in development and in an installation alike.
- `distJar` makes `app.jar` apart from the `jar` task, whose output other modules use: same classes, plus
  `Main-Class: dev.ftcsim.app.Main` and a `Class-Path` of the jars of the application's own `runtimeClasspath`, in the
  order `:app:run` has them.
- `distFolder` lays out `src\dist` (`FTC-Sim.cmd`, with Windows line endings whatever the checkout has, and
  `runtime-options`), the runtime, the licence notices and `lib`. `distZip` zips the folder under a top folder `FTC-Sim\`,
  with fixed timestamps and entry order. The same files give the same zip, byte for byte; the modules' own jars carry the
  time they were built, so two clean builds differ unless the convention plugin makes every jar reproducible.
- `thirdPartyNotices` writes `THIRD-PARTY-NOTICES.txt` and `licenses\` (below), from the same jars that fill `lib` and from
  the release file of the runtime's JDK.
- The Java options the application needs (`--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow`)
  are in `app\src\dist\runtime-options`: the launcher starts the application with them (`@runtime-options`), `:app:run` uses
  them, and `processResources` copies the file into `app.jar`, where the self test checks the running JVM against it. The
  major version (`runtime.properties`) is the toolchain's, filled in by Gradle.

`:app:test` builds the distribution first and runs `DistributionLayoutTest` (exactly what the folder and the zip hold, the
runtime's modules and what it leaves out, no absolute path, no jar twice, every entry resolves inside the folder),
`ThirdPartyNoticesTest` (below), `AppLogTest`, `SelfTestTest` and `DistributionEndToEndTest`. That one starts the real
distribution through `FTC-Sim.cmd`, from a folder with spaces and an umlaut in its path, with a redirected
`%LOCALAPPDATA%` and **no Java on the PATH and no JAVA_HOME**. Of the two copies it makes, one is the real distribution
(the self test on the runtime, warm and cold, a log per start, no process left, nothing written to `config`); in the other
`lib\app.jar` is a tiny program that prints how it was started and exits, throws or crashes the Java runtime, which proves
the hand-off: the options of `runtime-options` reach the JVM, the user's arguments arrive unchanged and in order, the Java
is `runtime\`, the exit code comes back, a crash report lands in `logs\hs_err_pid<pid>.log` and nowhere else, and a
folder without its runtime is reported (exit 79) and, for a double-click, waits for a key.

## How a start goes

1. `FTC-Sim.cmd` finds its own folder and checks that `runtime\bin\java.exe`, `lib\app.jar` and `runtime-options` are
   there. When they are not (what happens when the zip is opened and `FTC-Sim.cmd` double-clicked inside it) it says so,
   says to Extract All, and exits with 79.
2. It makes the logs folder (`%LOCALAPPDATA%\FTC-Sim\logs`) and starts the runtime's own Java:

   ```
   runtime\bin\java.exe @runtime-options -XX:ErrorFile=<logs>\hs_err_pid%p.log -XX:-CreateCoredumpOnCrash
         -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar lib\app.jar <the user's options>
   ```

   Options that the script does not know belong to the application: `--no-focus`, `--exit-after=<seconds>`,
   `--settings=<file>` for the window, `--self-test` alone. The working directory is the caller's and does not matter. The
   script's own console is the console of the program; the exit code of `FTC-Sim.cmd` is the application's (the self test
   returns 0 or 1, 2 for wrong options, the window 0).
3. The application (`Main`) opens its log, `logs\app-<yyyyMMdd-HHmmss>.log` (`AppLog`), tees standard output and error
   into it, and opens its window and, when a session is opened, starts the worker on the same Java (`RealHost`), reading
   `lib\ftcsim-classpath.properties` next to its jar.

**Why a manifest class path and not `lib\*`.** Both keep the command line short. A wildcard would put every jar of `lib`
on the application's class path: the worker's and the robot world's jars too, whose classes (an `android` package of the
shim, the SDK adapters) the window must not see, and in an order the JVM does not define. The manifest names exactly the
jars of the window, in the order of development, and keeps the command line short, far under `cmd.exe`'s 8,191. Windows'
260-character limit does not apply to the JVM, which opens files through extended paths; it applies to the folder the zip
is unpacked into, see above.

**Logging.** Everything the program prints, in the order it printed it, is written to `app-<time>.log` by the
application itself, and shown on the console as well; the Java runtime's own crash report is a file of its own,
`hs_err_pid<pid>.log`, in the same folder (`-XX:ErrorFile`), never beside the program or in the folder it was started
from. The 20 newest application logs are kept; the worker's logs (`worker-<time>-<id>.log`, 10 newest, and its crash
reports) are in the same folder and are the worker launcher's to keep. A student who has a problem sends that folder's
newest files. A failure before `Main` starts (a missing runtime, a Java that cannot start) has no log; the script says
what it found, in the console. If the log cannot be written the program says so on the console and goes on without it.

**Exit code and the console.** `FTC-Sim.cmd` waits for the program and returns its exit code. Started by double-click,
which the script sees as a start by Explorer (`cmd /c` in its own command line), a failure prints what to send and waits
for a key before the window closes (`pause`); set `FTCSIM_NO_PAUSE=1` to switch that off for a script. Unlike the earlier
PowerShell launcher, the console of a double-click is not closed after a few seconds but stays while FTC-Sim runs: a batch
file cannot both wait for the program, to show a failure, and let go of its own console. Nothing else about the window
changes.

**What this PC may forbid.** Programs in a folder under the user's profile (an application-control policy, a school's
AppLocker rules) or a virus scanner that quarantines `runtime\bin\java.exe` or a DLL beside it: `FTC-Sim.cmd` says the
runtime folder is missing (79), or the program stops with a message of Windows and the exit code that follows, and the
script's closing text tells to extract the zip again or have the folder allowed. Nothing is installed, so no policy
about installers applies.

## The self test

`FTC-Sim.cmd --self-test` (or `java @runtime-options -jar lib\app.jar --self-test`; without the options the self test
reports that the launcher did not pass them) opens no window and no OpenGL context. It checks: the Java is the major
version the application was built for, x64, Windows, and was started with the options of `runtime-options`; the Java is
the `runtime` folder of this installation (skipped, saying so, when the program runs from build folders, as `:app:run`
does); the window's classes and its native libraries (Skia, LWJGL) are on the class path; the class path file lists only
jars that exist; the real worker starts on the same Java through `RealHost.openLink()`, tells what can be asked of it and
ends with its link; the physics engine loads and steps (the DLL unpacked into `natives\<sha256>\joltjni.dll`); and no
process of its own is left. It prints `FTC-Sim self-test passed` and exits 0, or says what failed, one line a check, and
exits 1 (2: wrong options).

A session cannot be opened in a distribution (a robot program needs the FTC SDK, which comes with the team's own project),
so Jolt is loaded the way the worker loads it, through the worker's jars, in the self test's own process. It touches
`natives` and `logs` under `%LOCALAPPDATA%\FTC-Sim` and nothing else: no configuration, no program, no import.

## Licence notices

`THIRD-PARTY-NOTICES.txt` and the `licenses` folder beside it are in the folder and the zip. The task
`:app:thirdPartyNotices` (`app\notices.gradle.kts`, a script plugin applied by `app\build.gradle.kts`; no Gradle
plugin was added) writes them from the very jars that fill `lib`, so a jar that gets into `lib` is in the notices
without anybody remembering it. For each jar the file gives the library, its licence, the licence's address, the
project's page, and the notices the jar itself carries:

```
File: jolt-jni-Windows64-7.0.0-ReleaseSp.jar
  Library: com.github.stephengold:jolt-jni-Windows64:7.0.0
  Licence: MIT (MIT License)
  Licence URL: https://opensource.org/licenses/MIT
  Licence text: licenses/MIT-jolt-jni.txt
  Project: https://github.com/stephengold/jolt-jni
```

Its sections are: 1, FTC-Sim's own jars; 2, the libraries; 3, code that is built into a native library and is
licensed on its own (Jolt Physics in `joltjni.dll`, GLFW in `glfw.dll`, Skia and the ICU data in Skiko's jar); 4, the Java
runtime in `runtime\`: which JDK it was made from (vendor and build, read from that JDK's `release` file), that it is a
jlink image, and where its own licence texts are, `runtime\legal` (jlink copies the `legal` folder of each module it
links: `legal\java.base\LICENSE` is the licence of the runtime, for an OpenJDK build such as Eclipse Temurin the GPL 2
with the Classpath Exception, and the third-party parts have their texts beside it); 5, the list of licence texts. The
eight texts in `app\licenses` that the notices copy are the projects' own (Apache 2.0, the BSD and MIT licences with their
copyright holders, zlib, Unicode); the BSD and MIT licences name a copyright holder, so each project's own text is used,
never a generic one. (`GPL-2.0-with-classpath-exception.txt` stays in `app\licenses` for a library whose POM names that
licence; the runtime's licence is the one in its own `legal` folder.)

How the licence of a library is found: from the POM that sits beside the jar (read through Gradle's own artifact
resolution, with the parent POMs, as Maven does), mapped to Apache-2.0, BSD-3-Clause, MIT or
GPL-2.0-with-classpath-exception. Nothing is guessed. A jar whose POM names no licence, or one the build does not know,
or one without a text in `app\licenses`, fails the build with the jar's name:

```
The licence of these jars of the distribution cannot be determined, so the licence notices cannot be written:
  jolt-jni-Windows64-7.0.0.jar (com.github.stephengold:jolt-jni-Windows64:7.0.0): its POM names the licence 'MIT License' ... which the build does not recognise
```

The way out is a person reading that library's licence and adding it to the commented `overrides` table in
`app\notices.gradle.kts` (with the text in `app\licenses`); never silence. The output is deterministic: sorted, UTF-8,
no dates, no path of the build PC (deleting the output and building again gives the same bytes). A POM that is not in
the Gradle cache is fetched when the task runs, so the first notices build of a new library needs the network.

`ThirdPartyNoticesTest` (7 tests) checks the result: every jar of `lib` is covered by exactly one entry and every entry
is a jar of `lib`, in sorted order; every library entry has coordinates that match its jar, a licence with an http(s)
address and a text that exists; ASM, LWJGL and jolt-jni have their own project's text; every code that is said to be
inside a native library is in the jar that the notice names; the `licenses` folder holds exactly the texts the notices
point at, each with the words of the licence it is named for; the runtime section names the Java version of
`runtime\release` and `runtime/legal/java.base/LICENSE` exists; and the zip carries the notices and every text unchanged.

What this does not settle. FTC-Sim's own code has no licence yet: the repository has no `LICENSE` file and none was
invented, so section 1 says the owner has not chosen one; the owner must choose one, and the notices then say it. **The
JDK the runtime is made from decides the runtime's licence**: the development PC's toolchain JDK is Oracle's 25.0.1, whose
`legal` texts are Oracle's No-Fee Terms and Conditions, not the GPL; a release should be linked from an Eclipse Temurin 25
(`-Pftcsim.runtimeJdk=`), and the owner should read the terms of whatever JDK a release is made from. Skia and LWJGL's
other native libraries contain further open-source code whose notices are only referenced (Skia's `third_party`
folder, LWJGL's licence page), not listed one by one. The notices are metadata from the libraries' publishers, tested
for consistency; they are not legal advice.

## Measured on this PC (Ryzen 9 9950X, Windows 11; Oracle JDK 25.0.1 as the toolchain)

| What | Time |
|---|---|
| `runtime\bin\java.exe -jar lib\app.jar --self-test` in a console | 0.84 s |
| `FTC-Sim.cmd --self-test` with no Java on the PATH (three runs; the batch file adds nothing measurable) | 0.82 s, 0.82 s, 0.82 s, exit code 0 |
| `FTC-Sim.cmd --no-focus --exit-after=10` | 11.7 s, exit code 0, a non-empty log (the log line of `Main`) |
| `:app:runtimeImage` (jlink) | 3 s |
| `:app:dist` after a source change (runtime up to date) | about 12 s |

Starting costs no more than the application: there is no PowerShell, no search for a Java and no probe run before it
(the earlier launcher spent about 0.5 s on those on a warm cache, and 7 s with the 134.7 MiB download on a first start).

## Verification (phase 6)

On the development PC, with the distribution built by `:app:dist`:

- `FTC-Sim.cmd --self-test` in a process whose PATH was `%SystemRoot%\system32;%SystemRoot%` and whose `JAVA_HOME` was empty
  (`where java` finds none) passed and returned 0; `FTC-Sim.cmd --no-focus --exit-after=10 --settings=<file>` under the same
  environment returned 0 and wrote a log that is not empty.
- `.\gradlew.bat check` (the whole build, no Hornet): green with `-Xlint:all -Werror`.
- The Hornet-gated acceptance suite with the toolchain JDK (`-Pftcsim.hornet`, `-Pftcsim.hornetPackage`): 186 tests, 0 failed,
  0 skipped, 7 min 22 s. Then `RealHostHornetTest` (2) and `HornetPackageTest` (1) with `-Pftcsim.runtime=` on the bundled
  runtime as the JVM of the test and so of the worker: 3 tests, 0 failed, 0 skipped, 1 min 34 s. A runtime that does not
  exist makes Gradle refuse to start the test JVM, which shows the property is what picks the Java.

## Updating

Unzip the new release into a new folder and start it; delete the old one when convenient. Nothing is installed, so
there is nothing to uninstall, and `%LOCALAPPDATA%\FTC-Sim` is kept between releases: `natives`, `logs`, `programs` and
`import` (prepared team projects), `config`. A `jdks` folder left by a release that downloaded its Java (the earlier
launcher) is not used by this one and not removed: it may be deleted.

## What is not in it

- **A JDK.** The runtime is not a development kit: no compiler, no `jlink`, no debugging or monitoring tools, no
  `jdk.jfr`. A robot program that compiles code at run time (`javax.tools`) cannot run on it, and a debugger cannot attach
  to the worker through `jdk.jdwp.agent` (that module is small and can be added to `runtimeModules` if wanted).
- **The Hornet package and any team's code.** A team's project is imported on the PC (`project-import`), built with the
  team's own Gradle build on a JDK 17 to 21 (not the one FTC-Sim runs on; found by `JdkDiscovery`) and prepared into
  `%LOCALAPPDATA%\FTC-Sim\programs`. No robot description is bundled: the fixed robots are code (`RobotDesign`) and a
  project's mapping is its own `ftcsim.json`.
- **The Android SDK and the FTC SDK jars.** Importing needs an Android SDK on the PC (found by `AndroidSdkDiscovery`);
  the FTC SDK comes with the team's project.
- **An installer, a signature, automatic updates.** A zip from the internet carries the Mark of the Web: Windows may ask
  once before running `FTC-Sim.cmd` (Properties, Unblock, before unpacking, avoids it), and files that were unpacked from a
  zip with the mark may carry it too.

## Not verified

- **Windows SmartScreen and antivirus.** Whether an unpacked `runtime\bin\java.exe` and its DLLs from a zip are treated
  differently from the earlier extraction of a downloaded JDK into the user's cache (a different path, the same files from
  a different publisher: the jlink image is not Oracle's or Eclipse's signed distribution as a whole, though
  its files are unmodified) could not be tested here: the build PC has no such product.
- A clean PC (new Windows install, no Java, no Visual C++ runtime; the image brings its own C runtime DLLs), a school or
  company policy that blocks `cmd.exe`, programs in `%LOCALAPPDATA%` or unsigned programs, the double-click console
  behaviour with Explorer itself (the script's detection and the `pause` are tested with `cmd /c`, not with Explorer and
  a real window), a PC without a GPU driver that supports OpenGL, which the self test deliberately does not open, and an
  install folder with characters outside the Windows ANSI code page, which the Java launcher cannot open `app.jar` from.
- The licence notices by a person who knows the licences (they are generated from the libraries' own metadata and
  checked for consistency, see above), and a build with no network for a library whose POM is not yet in the Gradle cache.
