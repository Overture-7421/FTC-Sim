# The distribution and how FTC-Sim starts

A driver unzips `FTC-Sim.zip` anywhere, double-clicks `FTC-Sim.cmd`, and FTC-Sim opens, on a Windows 10 or 11 x64 PC
that has no Java, no network and no PowerShell policy to satisfy. `:app:dist` builds the folder and the zip: the
application's jars, a Java 25 runtime linked with `jlink` (`runtime\`), and `FTC-Sim.cmd`, a small batch file that starts
the application on that runtime. Decisions and measurements are in `decisions.md`, section 7.

## What is in the folder

| Path | What it is |
|---|---|
| `FTC-Sim.cmd` | the double-click entry point: starts `runtime\bin\java.exe`, hands back the exit code |
| `runtime-options` | the Java options the application needs, a Java argument file (also a resource of `app.jar`) |
| `runtime\` | the Java 25 runtime (`bin`, `conf`, `lib`, `legal`, `release`): 200 files, 51.3 MB |
| `THIRD-PARTY-NOTICES.txt`, `licenses\` | the licence notices and the licence texts they point at |
| `lib\app.jar` | the application; its manifest names `Main-Class` and the jars of the window (`Class-Path`) |
| `lib\ftcsim-classpath.properties` | `worker=` and `robot=` lines, bare jar names relative to the file |
| `lib\*.jar` | the window, Compose, Skia, LWJGL with natives, Kotlin, the worker, the simulator and its parts, jolt-jni Windows64, `robot-runtime`, `android-shim`, `sdk-common` ... |

291 files, 98.3 MB unpacked, zip 80.0 MiB; the runtime is most of the difference to a build without it, since the jars
hardly compress. No source, no tests, no Gradle cache, no JDK tools. Every jar is in `lib` once, however many of the
application's, the worker's and the robot world's class paths it is on; two different files with the same name fail the
build. The longest path inside the folder is 64 characters, so an install folder up to about 190 characters deep is safe
for tools that stop at Windows' 260-character path limit.

## The runtime

`runtime\` is a `jlink` image of the JDK that the project is compiled for: the toolchain of `ftcsim.java-conventions`
(`jdk = "25"` in `gradle\libs.versions.toml`), with its own `jlink.exe`, so the runtime is the Java the code was built
and tested on. `-Pftcsim.runtimeJdk=<JDK home>` names another JDK of the same major version (for a release, an Eclipse
Temurin 25; the task refuses another major version, or a JRE). Nothing is downloaded at run time.

The modules (`runtimeModules` in `app\runtime.gradle.kts`): `java.se`, which is every standard module, so that a team
library that needs `java.sql`, `java.naming`, `java.rmi` or `java.net.http` still runs, and the ones it does not name:
`jdk.unsupported` (`sun.misc.Unsafe`, used by LWJGL and the FTC SDK), `jdk.management`, `jdk.crypto.ec`, `jdk.zipfs`,
`jdk.net`, `jdk.httpserver` (a robot program's web server, such as Panels') and `jdk.charsets`. The options:
`--strip-debug --no-header-files --no-man-pages --compress zip-6`. There is no `--module-path`: jlink links the running
image of its own JDK (every JDK of Java 24 or later can). The image brings its own C runtime DLLs, so the PC needs no
Visual C++ runtime.

Sizes (Oracle JDK 25.0.1, unpacked / zipped): the runtime 51.3 MB / 35.8 MB; the whole JDK 362 MB; the `jdeps` floor
over the window's, the worker's and Hornet's jars 47.6 / 32.4 MB; with `jdk.localedata` 61.4 / 45.2 MB;
`ALL-MODULE-PATH` 92.2 / 70.6 MB. `java.se` stays because a standard module the `jdeps` list misses would fail with a
`NoClassDefFoundError` in team code; `jdk.localedata` is out because the worker runs with the en-US locale. The fallback,
if an image ever proves problematic, is `ALL-MODULE-PATH` in `runtimeModules` (41 MB bigger). The window, `--self-test`
and the real-host Hornet tests all run on the image (`-Pftcsim.runtime=app\build\dist\FTC-Sim\runtime`); no module was
missing.

## Building it

`.\gradlew.bat :app:dist` (VS Code: `ftc-sim: build distribution`) writes `app\build\dist\FTC-Sim\` and
`app\build\dist\FTC-Sim.zip`. The pieces, in `app\build.gradle.kts` and the script plugins it applies:

- `runtimeImage` (`LinkRuntime`, `app\runtime.gradle.kts`) runs the toolchain JDK's jlink into `build\dist-parts\runtime`;
  it is up to date until the JDK's `lib\modules` or `release`, the module list or the major version change, and fails
  when the JDK is not the major version the code is compiled for.
- `writeClasspath` and `writeDistClasspath` (`WriteClasspathFile`) write the class path file: absolute paths into the
  build folders for `:app:run` and the tests, bare names for the folder.
- `distJar` makes `app.jar` apart from the `jar` task (plus `Main-Class` and the window's `Class-Path`); `distFolder`
  lays out `src\dist` (`FTC-Sim.cmd` with Windows line endings whatever the checkout has, and `runtime-options`), the
  runtime, the notices and `lib`; `distZip` zips it under a top folder `FTC-Sim\` with fixed timestamps and entry order
  (the modules' own jars carry their build time, so two clean builds differ unless the convention plugin makes every
  jar reproducible); `thirdPartyNotices` writes the notices (below).
- The Java options (`--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow`) are in
  `app\src\dist\runtime-options`: the launcher, `:app:run` and the self test use them (`processResources` copies the file
  into `app.jar`). The major version (`runtime.properties`) is the toolchain's.

`:app:test` builds the distribution first and checks it, including the real hand-off through `FTC-Sim.cmd` from a folder
with spaces and an umlaut with no Java on the PATH (`docs/testing.md`).

## How a start goes

1. `FTC-Sim.cmd` finds its own folder and checks that `runtime\bin\java.exe`, `lib\app.jar` and `runtime-options` are
   there. When they are not (the zip was opened and `FTC-Sim.cmd` double-clicked inside it) it says to Extract All and
   exits with 79.
2. It makes the logs folder (`%LOCALAPPDATA%\FTC-Sim\logs`) and starts the runtime's own Java:

   ```
   runtime\bin\java.exe @runtime-options -XX:ErrorFile=<logs>\hs_err_pid%p.log -XX:-CreateCoredumpOnCrash
         -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar lib\app.jar <the user's options>
   ```

   Options the script does not know belong to the application: `--no-focus`, `--exit-after=<seconds>`,
   `--settings=<file>` for the window, `--self-test` alone. The script's console is the program's; the exit code of
   `FTC-Sim.cmd` is the application's (the self test returns 0 or 1, 2 for wrong options, the window 0).
3. The application (`Main`) opens `logs\app-<yyyyMMdd-HHmmss>.log` (`AppLog`), tees standard output and error into it,
   opens its window and, when a session is opened, starts the worker on the same Java (`RealHost`), reading
   `lib\ftcsim-classpath.properties` next to its jar.

**Why a manifest class path and not `lib\*`.** A wildcard would put every jar of `lib` on the application's class path:
the worker's and the robot world's too, whose classes (an `android` package of the shim, the SDK adapters) the window
must not see, in an order the JVM does not define. The manifest names exactly the window's jars in development order.

**Logging.** Everything the program prints is written to `app-<time>.log` by the application itself (the 20 newest are
kept) and shown on the console as well. The Java runtime's crash report is its own file, `hs_err_pid<pid>.log`, in the
same folder, as are the worker's logs (`worker-<time>-<id>.log`, 10 newest, and its crash reports), which the worker
launcher keeps. A student who has a problem sends that folder's newest files. A failure before `Main` starts (a missing
runtime, a Java that cannot start) has no log; the script says what it found, on the console. If the log cannot be
written the program says so and goes on without it.

**Exit code and the console.** `FTC-Sim.cmd` waits for the program and returns its exit code. Started by double-click
(the script sees `cmd /c` in its own command line), a failure prints what to send and waits for a key (`pause`); set
`FTCSIM_NO_PAUSE=1` to switch that off for a script. The console of a double-click stays open while FTC-Sim runs: a batch
file cannot both wait for the program, to show a failure, and let go of its own console.

**What this PC may forbid.** An application-control policy (a school's AppLocker rules) on programs in the user's
profile, or a virus scanner that quarantines `runtime\bin\java.exe` or a DLL beside it: the script says the runtime
folder is missing (79), or the program stops with a message of Windows, and the script's closing text tells to extract
the zip again or have the folder allowed. Nothing is installed, so no policy about installers applies.

## The self test

`FTC-Sim.cmd --self-test` (or `java @runtime-options -jar lib\app.jar --self-test`; without the options it reports that
the launcher did not pass them) opens no window and no OpenGL context. It checks that:

- the Java is the major version the application was built for, x64, Windows, started with the options of
  `runtime-options`, and the `runtime` folder of this installation (skipped, saying so, when running from build folders);
- the window's classes and its native libraries (Skia, LWJGL) are on the class path, and the class path file lists only
  jars that exist;
- the real worker starts on the same Java through `RealHost.openLink()`, tells what can be asked of it and ends with its
  link;
- the physics engine loads and steps (the DLL unpacked into `natives\<sha256>\joltjni.dll`), loaded the way the worker
  loads it, because a session cannot be opened in a distribution (a robot program needs the FTC SDK, which comes with
  the team's project);
- no process of its own is left.

It prints `FTC-Sim self-test passed` and exits 0, or says what failed, one line a check, and exits 1 (2: wrong options).
It touches only `natives` and `logs` under `%LOCALAPPDATA%\FTC-Sim`: no configuration, no program, no import.

## Licence notices

`THIRD-PARTY-NOTICES.txt` and the `licenses` folder are in the folder and the zip. `:app:thirdPartyNotices`
(`app\notices.gradle.kts`) writes them from the very jars that fill `lib`, so a jar that gets into `lib` is in the notices
without anybody remembering it: library coordinates, licence and address, project page and the notices the jar carries.
The sections are: FTC-Sim's own jars; the libraries; code built into a native library and licensed on its own (Jolt
Physics in `joltjni.dll`, GLFW in `glfw.dll`, Skia and the ICU data in Skiko's jar); the Java runtime (the JDK it was
made from, read from that JDK's `release` file, and `runtime\legal`, where jlink copies each module's licence texts); the
list of licence texts. The texts in `app\licenses` are each project's own, never a generic one.

A library's licence is read from the POM beside the jar (through Gradle's artifact resolution, with parent POMs) and
mapped to Apache-2.0, BSD-3-Clause, MIT or GPL-2.0-with-classpath-exception. Nothing is guessed: a jar whose POM names no
licence, one the build does not know, or one without a text in `app\licenses` fails the build with the jar's name. The way
out is a person reading that library's licence and adding it to the commented `overrides` table in
`app\notices.gradle.kts` (with the text in `app\licenses`); never silence. A POM that is not in the Gradle cache is
fetched when the task runs, so the first notices build of a new library needs the network.

What this does not settle. **FTC-Sim's own code has no licence yet**: the repository has no `LICENSE` file and none was
invented; the owner must choose one, and the notices then say it. **The JDK the runtime is made from decides the
runtime's licence**: the development PC's toolchain JDK is Oracle's 25.0.1, whose `legal` texts are Oracle's No-Fee Terms
and Conditions, not the GPL; a release should be linked from an Eclipse Temurin 25 (`-Pftcsim.runtimeJdk=`). Skia's and
LWJGL's other native libraries contain further open-source code whose notices are only referenced, not listed one by one.
The notices are metadata from the libraries' publishers, tested for consistency; they are not legal advice.

## Updating

Unzip the new release into a new folder and start it; delete the old one when convenient. Nothing is installed, so
there is nothing to uninstall, and `%LOCALAPPDATA%\FTC-Sim` is kept between releases (`natives`, `logs`, `programs`,
`import`, `config`). A `jdks` folder left by a release that downloaded its Java is not used and may be deleted.

## What is not in it

- **A JDK.** No compiler, no `jlink`, no debugging or monitoring tools, no `jdk.jfr`. A robot program that compiles code
  at run time (`javax.tools`) cannot run on it, and a debugger cannot attach to the worker through `jdk.jdwp.agent` (a
  small module that can be added to `runtimeModules` if wanted).
- **The Hornet package and any team's code.** A team's project is imported on the PC (`project-import`), built with the
  team's own Gradle build on a JDK 17 to 21 (found by `JdkDiscovery`) and prepared into
  `%LOCALAPPDATA%\FTC-Sim\programs`. No robot description is bundled: the fixed robots are code (`RobotDesign`) and a
  project's mapping is its own `ftcsim.json`.
- **The Android SDK and the FTC SDK jars.** Importing needs an Android SDK on the PC (found by `AndroidSdkDiscovery`);
  the FTC SDK comes with the team's project.
- **An installer, a signature, automatic updates.** A zip from the internet carries the Mark of the Web: Windows may ask
  once before running `FTC-Sim.cmd` (Properties, Unblock, before unpacking, avoids it).

## Not verified

- **Windows SmartScreen and antivirus.** Whether an unpacked `runtime\bin\java.exe` and its DLLs from a zip are treated
  differently from the signed distribution they came from (the jlink image's files are unmodified, but the image as a
  whole is nobody's signed build) could not be tested: the build PC has no such product.
- A clean PC (new Windows install, no Java, no Visual C++ runtime), a policy that blocks `cmd.exe`, programs in
  `%LOCALAPPDATA%` or unsigned programs, the double-click console behaviour with Explorer itself (the script's detection
  and `pause` are tested with `cmd /c`), a PC without a GPU driver that supports OpenGL (which the self test deliberately
  does not open), and an install folder with characters outside the Windows ANSI code page, which the Java launcher
  cannot open `app.jar` from.
- The licence notices by a person who knows the licences, and a notices build with no network for a library whose POM is
  not yet in the Gradle cache.
