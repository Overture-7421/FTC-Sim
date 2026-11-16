# The distribution and how FTC-Sim starts

What `:app:dist` produces, how `FTC-Sim.cmd` starts the application, and what the zip does and does not hold; read it when you change startup, the bundled Java runtime, the zip or the licence notices.

A driver unzips `FTC-Sim.zip` anywhere and double-clicks `FTC-Sim.cmd` on a Windows 10 or 11 x64 PC with no Java, no network and no PowerShell policy to satisfy. Decisions and measurements: `decisions.md`, section 7.

## What is in the folder

| Path | What it is |
|---|---|
| `FTC-Sim.cmd` | The entry point: starts `runtime\bin\java.exe`, hands back its exit code. |
| `runtime-options` | The Java options the application needs, a Java argument file (also a resource of `app.jar`). |
| `runtime\` | The Java 25 runtime (`bin`, `conf`, `lib`, `legal`, `release`): 200 files, 51.3 MB. |
| `THIRD-PARTY-NOTICES.txt`, `licenses\` | The licence notices and the texts they point at. |
| `lib\app.jar` | The application; its manifest names `Main-Class` and the window's jars (`Class-Path`). |
| `lib\ftcsim-classpath.properties` | `worker=` and `robot=` lines, bare jar names relative to the file ([app.md](app.md)). |
| `lib\*.jar` | Window, Compose, Skia, LWJGL with natives, Kotlin, worker, simulator and parts, jolt-jni, `robot-runtime`, `android-shim`, `sdk-common`, ... |

291 files, 98.3 MB unpacked, zip 80.0 MiB (the runtime is most of the difference to a build without it; jars hardly compress). No source, tests, Gradle cache or JDK tools.

- Every jar is in `lib` once, however many class paths it is on; two different files with the same name fail the build.
- The longest path inside is 64 characters, so an install folder up to about 190 characters deep stays under Windows' 260-character limit.

## The runtime

`runtime\` is a `jlink` image of the JDK the project is compiled for (`jdk = "25"` in `gradle\libs.versions.toml`, the toolchain of `ftcsim.java-conventions`, using that JDK's own `jlink.exe`), so it is the Java the code was built and tested on. Nothing is downloaded at run time.

- `-Pftcsim.runtimeJdk=<JDK home>` names another JDK of the same major version (for a release: Eclipse Temurin 25). The task refuses another major version or a JRE.
- `runtimeModules` (`app\runtime.gradle.kts`): `java.se` (every standard module, so a team library needing `java.sql`, `java.naming`, `java.rmi` or `java.net.http` still runs) plus `jdk.unsupported` (`sun.misc.Unsafe`: LWJGL, FTC SDK), `jdk.management`, `jdk.crypto.ec`, `jdk.zipfs`, `jdk.net`, `jdk.httpserver` (a robot program's web server, such as Panels') and `jdk.charsets`.
- jlink options: `--strip-debug --no-header-files --no-man-pages --compress zip-6`. No `--module-path`: jlink links the running image of its own JDK (any JDK 24 or later can). The image brings its own C runtime DLLs, so the PC needs no Visual C++ runtime.
- **`java.se` stays** because a standard module missed by the `jdeps` list would fail with `NoClassDefFoundError` in team code. **`jdk.localedata` is out** because the worker runs with the en-US locale. Fallback if the image proves problematic: `ALL-MODULE-PATH` in `runtimeModules` (41 MB bigger).

| Image (Oracle JDK 25.0.1) | Unpacked / zipped (MB) |
|---|---|
| Used (`runtimeModules`) | 51.3 / 35.8 |
| `jdeps` floor over window, worker and Hornet jars | 47.6 / 32.4 |
| With `jdk.localedata` | 61.4 / 45.2 |
| `ALL-MODULE-PATH` | 92.2 / 70.6 |
| The whole JDK | 362 |

The window, `--self-test` and the real-host Hornet tests (`-Pftcsim.runtime=app\build\dist\FTC-Sim\runtime`) all run on the image; no module was missing.

## Building it

`.\gradlew.bat :app:dist` (VS Code: `ftc-sim: build distribution`) writes `app\build\dist\FTC-Sim\` and `app\build\dist\FTC-Sim.zip`. Tasks, in `app\build.gradle.kts` and its script plugins:

- `runtimeImage` (`LinkRuntime`) runs the toolchain JDK's jlink into `build\dist-parts\runtime`; it is up to date until the JDK's `lib\modules` or `release`, the module list or the major version change, and fails on the wrong major version.
- `writeClasspath` / `writeDistClasspath` (`WriteClasspathFile`) write the class path file: absolute paths for `:app:run` and tests, bare names for the folder.
- `distJar` makes `app.jar` apart from `jar` (plus `Main-Class` and the window's `Class-Path`). `distFolder` lays out `src\dist` (`FTC-Sim.cmd` with Windows line endings whatever the checkout has, `runtime-options`), the runtime, the notices and `lib`. `distZip` zips it under `FTC-Sim\` with fixed timestamps and entry order (two clean builds differ unless the convention plugin makes every module jar reproducible). `thirdPartyNotices` is below.
- The Java options (`--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow`) live in `app\src\dist\runtime-options`; the launcher, `:app:run` and the self test use them (`processResources` copies the file into `app.jar`). The major version (`runtime.properties`) is the toolchain's.

`:app:test` builds the distribution first and checks it, including the real hand-off through `FTC-Sim.cmd` from a folder with spaces and an umlaut and no Java on the PATH (`docs/testing.md`).

## How a start goes

1. `FTC-Sim.cmd` finds its folder and checks `runtime\bin\java.exe`, `lib\app.jar` and `runtime-options`. If they are missing (the zip was opened and the script double-clicked inside it) it says to Extract All and exits 79.
2. It makes `%LOCALAPPDATA%\FTC-Sim\logs` and starts the runtime's Java:

   ```
   runtime\bin\java.exe @runtime-options -XX:ErrorFile=<logs>\hs_err_pid%p.log -XX:-CreateCoredumpOnCrash
         -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar lib\app.jar <the user's options>
   ```

   The script passes the user's options to the application unchanged: `--no-focus`, `--exit-after=<seconds>`, `--settings=<file>` for the window; `--self-test` alone. The console is the program's. The exit code is the application's (self test 0 or 1, 2 for wrong options; window 0).
3. `Main` opens `logs\app-<yyyyMMdd-HHmmss>.log` (`AppLog`), tees stdout and stderr into it, opens the window and, when a session opens, starts the worker on the same Java (`RealHost`), reading `lib\ftcsim-classpath.properties`.

- **Why a manifest class path and not `lib\*`:** a wildcard would put every jar on the application's class path, including the worker's and the robot world's, whose classes (the shim's `android` package, the SDK adapters) the window must not see, in an order the JVM does not define. The manifest names exactly the window's jars.
- **Logging:** the application writes everything it prints to `app-<time>.log` (20 newest kept) and to the console. The runtime's crash report is `hs_err_pid<pid>.log` in the same folder, as are the worker's logs and crash reports (10 newest logs, kept by the worker launcher). A student with a problem sends that folder's newest files. A failure before `Main` (missing runtime, a Java that cannot start) has no log; the script says what it found on the console. If the log cannot be written the program says so and goes on.
- **Exit code and console:** the script waits and returns the exit code. When started by double-click (it sees `cmd /c` in its own command line), a failure prints what to send and waits for a key (`pause`); `FTCSIM_NO_PAUSE=1` switches that off. The double-click console stays open while FTC-Sim runs, because a batch file cannot both wait for the program (to show a failure) and release its console.
- **What a PC may forbid:** an application-control policy (a school's AppLocker) on programs in the user profile, or a scanner that quarantines `runtime\bin\java.exe` or a DLL beside it. Then the script says the runtime folder is missing (79) or Windows stops the program, and the script's closing text says to extract again or have the folder allowed. Nothing is installed, so no installer policy applies.

## The self test

`FTC-Sim.cmd --self-test` (or `java @runtime-options -jar lib\app.jar --self-test`; without the options it reports that the launcher did not pass them) opens no window and no OpenGL context. It checks that:

- the Java is the major version the application was built for, x64, Windows, started with the options of `runtime-options`, and the `runtime` folder of this installation exists (skipped, saying so, when running from build folders);
- the window's classes and native libraries (Skia, LWJGL) are on the class path, and the class path file lists only existing jars;
- the real worker starts on the same Java through `RealHost.openLink()`, reports what can be asked of it and ends with its link;
- the physics engine loads and steps (the DLL unpacked into `natives\<sha256>\joltjni.dll`), loaded the way the worker loads it, because a session cannot open in a distribution (a robot program needs the FTC SDK, which comes with the team's project);
- no process of its own is left.

It prints `FTC-Sim self-test passed` and exits 0, or one line per failed check and exits 1 (2: wrong options). It touches only `natives` and `logs` under `%LOCALAPPDATA%\FTC-Sim`.

## Licence notices

`THIRD-PARTY-NOTICES.txt` and `licenses\` are in the folder and zip. `:app:thirdPartyNotices` (`app\notices.gradle.kts`) writes them from the very jars that fill `lib`, so a jar that gets into `lib` is in the notices automatically: coordinates, licence and address, project page and notices the jar carries. Sections: FTC-Sim's own jars; the libraries; code built into native libraries and licensed on its own (Jolt Physics in `joltjni.dll`, GLFW in `glfw.dll`, Skia and ICU data in Skiko's jar); the Java runtime (the JDK it was made from, read from that JDK's `release`, and `runtime\legal`, where jlink copies each module's licence texts); the list of licence texts. The texts in `app\licenses` are each project's own, never generic.

- A library's licence is read from the POM beside the jar (Gradle artifact resolution, with parent POMs) and mapped to Apache-2.0, BSD-3-Clause, MIT or GPL-2.0-with-classpath-exception.
- **Nothing is guessed.** A jar whose POM names no licence, one the build does not know, or one without a text in `app\licenses` fails the build with the jar's name. The fix is a person reading that library's licence and adding it to the commented `overrides` table in `app\notices.gradle.kts` (with its text in `app\licenses`); never silence it.
- A POM missing from the Gradle cache is fetched when the task runs, so the first notices build of a new library needs the network.

Open points:

- **FTC-Sim's own code has no licence yet.** The repository has no `LICENSE` file and none was invented; the owner must choose one, and the notices will then say it.
- **The JDK the runtime is made from decides the runtime's licence.** The development toolchain JDK is Oracle's 25.0.1, whose `legal` texts are Oracle's No-Fee Terms and Conditions, not the GPL. A release should link from Eclipse Temurin 25 (`-Pftcsim.runtimeJdk=`).
- Skia's and LWJGL's other native libraries contain further open-source code whose notices are referenced, not listed one by one.
- The notices are publishers' metadata, tested for consistency; they are not legal advice.

## Updating

Unzip the new release into a new folder and start it; delete the old one when convenient. Nothing is installed, so nothing is uninstalled. `%LOCALAPPDATA%\FTC-Sim` (`natives`, `logs`, `programs`, `import`, `config`) is kept between releases. A `jdks` folder left by a release that downloaded its Java is unused and may be deleted.

## What is not in it

- **A JDK.** No compiler, `jlink`, debugging or monitoring tools, no `jdk.jfr`. A robot program that compiles code at run time (`javax.tools`) cannot run, and a debugger cannot attach to the worker through `jdk.jdwp.agent` (a small module that can be added to `runtimeModules`).
- **The Hornet package and any team's code.** A project is imported on the PC ([prepared-program.md](prepared-program.md)), built with the team's own Gradle on a JDK 17 to 21 (`JdkDiscovery`) and prepared into `%LOCALAPPDATA%\FTC-Sim\programs`. No robot description is bundled: the fixed robots are code (`RobotDesign`), a project's mapping is its own `ftcsim.json`.
- **The Android SDK and the FTC SDK jars.** Importing needs an Android SDK on the PC (`AndroidSdkDiscovery`); the FTC SDK comes with the team's project.
- **An installer, a signature, automatic updates.** A zip from the internet carries the Mark of the Web, so Windows may ask once before running `FTC-Sim.cmd` (Properties, Unblock, before unpacking, avoids it).

## Not verified

- **SmartScreen and antivirus.** Whether an unpacked `runtime\bin\java.exe` and its DLLs from a zip are treated differently from the signed distribution they came from (the image's files are unmodified, but the image as a whole is nobody's signed build); the build PC has no such product.
- A clean PC (new Windows, no Java, no Visual C++ runtime); a policy that blocks `cmd.exe`, programs in `%LOCALAPPDATA%` or unsigned programs; the double-click console with Explorer itself (script detection and `pause` are tested with `cmd /c`); a PC without an OpenGL-capable GPU driver (the self test deliberately does not open one); an install folder with characters outside the Windows ANSI code page, which the Java launcher cannot open `app.jar` from.
- The licence notices by a person who knows the licences, and a notices build with no network for a library whose POM is not in the Gradle cache.
