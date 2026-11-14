# The distribution and how FTC-Sim starts

A driver unzips `FTC-Sim.zip` anywhere, double-clicks `FTC-Sim.cmd`, and FTC-Sim opens, on a Windows 10 or 11 x64 PC
that has no Java. `:app:dist` builds the folder and the zip; `launcher\scripts` (PowerShell, no Java needed) finds or
downloads the pinned JDK and starts the application with it. Decisions and measurements are in `decisions.md`, section 7.

## What is in the folder

```
FTC-Sim\
  FTC-Sim.cmd            the double-click entry point (starts FTC-Sim.ps1 with Windows PowerShell 5.1)
  FTC-Sim.ps1            finds or downloads the pinned JDK, starts the application, passes on its exit code
  jdk-manifest.json      the pin: Temurin 25.0.5+8 (release and SHA-256), windows/x64, where to download it, the options the application needs
  ftcsim\*.ps1           the libraries of the script (discovery, download, safe unpacking, launch, messages)
  THIRD-PARTY-NOTICES.txt  every jar of lib: its library, licence and project; the Java runtime that is downloaded
  licenses\              the 9 licence texts that file points at
  lib\
    app.jar              the application; its manifest names Main-Class and the jars of the window (Class-Path)
    ftcsim-classpath.properties   worker= and robot= lines, bare jar names relative to the file
    *.jar                81 more: the window, Compose, Skia, LWJGL with natives-windows, Kotlin, the worker,
                         the simulator and its parts, jolt-jni Windows64, robot-runtime, android-shim, sdk-common ...
```

104 files, 49.2 MB unpacked, zip 44.4 MiB (jars hardly compress). No source, no tests, no Gradle cache, no JDK, no launcher
jar. Every jar is in `lib` once, however many of the application's, the worker's and the robot world's class paths it is on
(`sim-contracts.jar` is on all three); two different files with the same name fail the build. The longest path inside the
folder is `lib\lifecycle-viewmodel-savedstate-desktop-2.11.0.jar`, 53 characters, so an install folder up to about 200
characters deep is safe for tools that stop at Windows' 260-character path limit.

## Building it

`.\gradlew.bat :app:dist` (VS Code: `ftc-sim: build distribution`) writes `app\build\dist\FTC-Sim\` and
`app\build\dist\FTC-Sim.zip`. The pieces, all in `app\build.gradle.kts`:

- `writeClasspath` and `writeDistClasspath` are one task type, `WriteClasspathFile`: the first writes absolute paths into
  the build folders for `:app:run` and the tests, the second the bare names for the folder. The format is the one
  `AppClasspath` reads, in development and in an installation alike.
- `distJar` makes `app.jar` apart from the `jar` task, whose output other modules use: same classes, plus
  `Main-Class: dev.ftcsim.app.Main` and a `Class-Path` of the 66 jars of the application's own `runtimeClasspath`, in the
  order `:app:run` has them.
- `distFolder` lays out `launcher\scripts` (referenced by path: `app` has no dependency on `launcher`, which has none on
  anything), the licence notices and `lib`. `distZip` zips the folder under a top folder `FTC-Sim\`, with fixed timestamps
  and entry order.
  The same jars give the same zip, byte for byte (checked by rebuilding); the modules' own jars carry the time they were
  built, so two clean builds differ unless the convention plugin makes every jar reproducible.
- `thirdPartyNotices` writes `THIRD-PARTY-NOTICES.txt` and `licenses\` (below), from the same jars that fill `lib`.
- The Java options the application needs (`--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow`)
  are in `jdk-manifest.json` under `application.jvmArguments`: the script starts the application with them, `:app:run`
  uses them, and the self test checks the running JVM has them.

`:app:test` builds the distribution first and runs `DistributionLayoutTest` (exactly what the folder and the zip hold,
no absolute path, no jar twice, every entry resolves inside the folder), `ThirdPartyNoticesTest` (below) and
`DistributionEndToEndTest`. That one starts the real distribution through `FTC-Sim.cmd`, from a folder with spaces and an
umlaut in its path, with a redirected `%LOCALAPPDATA%` and no Java on the PATH. It has three
tiers, each of which says in its skip message what it needs from this PC and none of which downloads anything:
(A) in a copy of the distribution whose pin is cleared, the JDK 25 of this PC runs the self test, and a real JVM crash,
an exception and an exit code all reach the log and the caller; (B) with the real, unmodified manifest, the pinned build
from the user's own FTC-Sim cache (it is there once `FTC-Sim.cmd` has run on this PC; skipped otherwise) passes the self
test; (C) the real manifest refuses this PC's other build of Java 25 with its reason and starts nothing (skipped when the
PC has no other build).

## How a start goes

1. `FTC-Sim.cmd` starts `FTC-Sim.ps1` with `-ExecutionPolicy Bypass` for that one process; it calls Windows' own
   `find.exe` and `powershell.exe` by full path, because a `find.exe` of Git for Windows can come first on the PATH.
   When PowerShell cannot be started the wrapper says so and exits with 79; so it does when `FTC-Sim.ps1` is not beside
   it, which is what happens when the zip is opened and `FTC-Sim.cmd` double-clicked inside it: it says to Extract All.
2. Stage 0 looks for a runtime of exactly the pinned build, Temurin 25.0.5+8 (JAVA_HOME, PATH, the registry, Program
   Files, Android Studio, the FTC-Sim cache; a JDK or a JRE, of any vendor, as long as `java.runtime.version` is that
   build). Every other Java it finds, another build of 25 included, is reported with its reason and ignored; none is
   ever used silently. When none qualifies it downloads that release by name into `%LOCALAPPDATA%\FTC-Sim\jdks` and
   refuses an archive whose SHA-256 is not the pinned one. See `decisions.md`, section 7. Failures are explained in the
   console, which stays open for a double-click, with exit codes 80 to 94 (`launcher\scripts\ftcsim\Messages.ps1`).
3. It starts `javaw.exe` (`java.exe` when a runtime has no `javaw.exe`) of that runtime:

   ```
   javaw <application.jvmArguments> -XX:+ErrorFileToStdout -XX:-CreateCoredumpOnCrash
         -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar lib\app.jar <the user's options>
   ```

   Options that stage 0 does not know belong to the application: `--no-focus`, `--exit-after=<seconds>`,
   `--settings=<file>` for the window, `--self-test` alone. The working directory is the caller's and does not matter.
4. The application opens its window and, when a session is opened, starts the worker on the same Java (`RealHost`),
   reading `lib\ftcsim-classpath.properties` next to its jar.

**Why a manifest class path and not `lib\*`.** Both keep the command line short. A wildcard would put all 82 jars on the
application's class path: the worker's and the robot world's jars too, whose classes (an `android` package of the shim,
the SDK adapters) the window must not see, and in an order the JVM does not define. The manifest names exactly the 66 jars
of the window, in the order of development, and keeps the command line at about 470 characters with typical paths, far
under `cmd.exe`'s 8,191 and CreateProcess's 32,767. Windows' 260-character limit does not apply to the JVM, which opens
files through extended paths; it applies to the folder the zip is unpacked into, see above.

**Logging.** The program runs under a hidden `cmd.exe /d /v:off /s /c "... > log 2>&1"`, which gives it one shared handle
for output and errors: what it writes, in the order it wrote it, and the Java runtime's own crash report
(`-XX:+ErrorFileToStdout`, instead of an `hs_err` file or a minidump beside the program) end up in
`%LOCALAPPDATA%\FTC-Sim\logs\app-<yyyyMMdd-HHmmss>.log`. The console shows the same text while the program runs. The
20 newest of those logs are kept; the worker's logs (`worker-<time>-<id>.log`, 10 newest, and its crash reports) are in
the same folder and are the application's to keep. A student who has a problem sends that folder's newest files.
`javaw.exe` has no console of its own, so no black window stays open. One caveat measured on this PC: a failure of the Java
launcher itself (a corrupt jar, an unknown option) is shown by `javaw.exe` as a message box and waits for OK, with only
part of the message in the log; stage 0 checks the jar exists and the runtime runs before it starts, which leaves little
that can still fail there.

**Exit code and the console.** Started with options, or from a terminal or script, stage 0 waits and returns the
application's exit code (the self test returns 0 or 1, the window 0). Started by double-click, which the wrapper sees as a
start by Explorer without options, it passes `-Detach`: the console closes once the application has run six seconds, and an
application that ends sooner, or fails, keeps it open (`pause`) with what the program wrote. Set `FTCSIM_NO_PAUSE=1` to
switch both off.

**What this PC may forbid.** Execution blocked (79), PowerShell in a restricted language mode (81), a proxy that is needed
(`-ProxyUrl`, otherwise the system's), no network (83), an application-control policy that blocks programs in the cache
folder (89): each has its own message and exit code and none is worked around. A PC that already has exactly the pinned
Temurin build installed for all users needs no download; the messages for the IT administrator name it by
`runtime.exactRelease` in `jdk-manifest.json`, because a newer Temurin 25 would be refused too.

## The self test

`FTC-Sim.cmd --self-test` (or `java <the manifest's options> -jar lib\app.jar --self-test`; without the options the self
test reports that the launcher did not pass them) opens no window and no OpenGL context. It checks:
the Java is the pinned version, x64, Windows, and was started with the manifest's options; the window's classes and its
native libraries (Skia, LWJGL) are on the class path; the class path file lists only jars that exist; the real worker
starts on the same Java through `RealHost.openLink()`, tells what can be asked of it and ends with its link; the physics
engine loads and steps (the DLL unpacked into `natives\<sha256>\joltjni.dll`); and no process of its own is left. It prints
`FTC-Sim self-test passed` and exits 0, or says what failed, one line a check, and exits 1 (2: wrong options).

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

Its sections are: 1, FTC-Sim's own jars (18); 2, the libraries (64); 3, code that is built into a native library and is
licensed on its own (Jolt Physics in `joltjni.dll`, GLFW in `glfw.dll`, Skia and the ICU data in Skiko's jar);
4, the Java runtime that is downloaded, which is not in the zip: the pinned Temurin release, GPL 2 with the Classpath
Exception, and where its source is; 5, the list of licence texts. The nine texts in `app\licenses` are the projects'
own (Apache 2.0, the BSD and MIT licences with their copyright holders, zlib, Unicode, and the GPL text that ships in
the Temurin zip); the BSD and MIT licences name a copyright holder, so each project's own text is used, never a
generic one.

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
point at, each with the words of the licence it is named for; the pinned Java release is named in section 4; and the
zip carries the notices and every text unchanged.

What this does not settle. FTC-Sim's own code has no licence yet: the repository has no `LICENSE` file and none was
invented, so section 1 says the owner has not chosen one; the owner must choose one, and the notices then say it. Skia
and LWJGL's other native libraries contain further open-source code whose notices are only referenced
(Skia's `third_party` folder, LWJGL's licence page), not listed one by one. The notices are metadata from the
libraries' publishers, tested for consistency; they are not legal advice.

## Measured on this PC (Ryzen 9 9950X, Windows 11; an Oracle JDK 25.0.1 and Android Studio's JBR 21 are installed and refused by the pin)

| What | Time |
|---|---|
| `java -jar lib\app.jar --self-test`, no stage 0 | 0.9 s |
| `FTC-Sim.cmd --self-test`, the pinned JDK in FTC-Sim's own cache (three runs) | 1.42 s, 1.45 s, 1.46 s, no download |
| the same on a PC without the pinned build (first run on this PC's own cache): download 134.7 MiB, verify, unpack 431 files / 290 MB, check, then the self test | 7 s, exit code 0 |
| `FTC-Sim.cmd --no-focus --exit-after=8`: window title seen / process ended (measured before the pin; the start-up path is the same) | 2.6 s / 11.4 s, exit code 0, empty log |

Stage 0 costs about 0.5 s when the pinned JDK is in the cache (PowerShell, and running that Java once to prove it); the
first start on a PC with other Javas also runs each of them, to say why it is not used. A slower disk or a virus scanner
will cost more, on the first unpacking most of all.

## Updating

Unzip the new release into a new folder and start it; delete the old one when convenient. Nothing is installed, so
there is nothing to uninstall, and `%LOCALAPPDATA%\FTC-Sim` is kept between releases: `jdks` (the downloaded Java, which
a release with another pin does not use and does not remove), `natives`, `logs`, `programs` and `import` (prepared team
projects), `config`. Deleting `jdks` makes the next start download the JDK again.

## Moving the pinned JDK

`jdk-manifest.json` pins one Temurin build with two values, `runtime.exactRelease` (`jdk-25.0.5+8`) and
`runtime.exactSha256`. Stage 0 uses only a Java whose `java.runtime.version` is that build, downloads that release by
name, and refuses an archive, or a service answer, that has another name or another hash (exit 86 before any download,
85 after one). It is moved deliberately, in three steps:

1. `.\launcher\tools\Update-JdkPin.ps1` (the newest Temurin of the pinned major version) or
   `.\launcher\tools\Update-JdkPin.ps1 -Release jdk-25.0.6+7`. It asks the Adoptium API, downloads the archive, hashes
   it, compares with the checksum the API published, and prints the two values. Compare them with the release page and
   its `.sha256.txt` on https://github.com/adoptium/temurin25-binaries/releases.
2. The same command with `-Apply` writes those two values, and nothing else, into `launcher\scripts\jdk-manifest.json`
   (and reads the manifest back to validate it).
3. `.\gradlew.bat :launcher:check :app:check`, then `FTC-Sim.cmd --self-test` once with the real service, so that the
   real download of the new build is tried (the tests never download it). The licence notices name the release from
   the manifest and follow it.

The launcher's tests take the release, the hash and the archive name from the shipped manifest, so they need no change.
`Stage0PinTest` (9 tests, against the fake Adoptium service) proves what the pin does: its shape; a foreign Java 25.0.1
and a Java 21 are ignored with their reasons and the pinned release is downloaded by name; the right build is used
without any network; another release name or another checksum from the service is refused before a download; a service
that no longer has the release says FTC-Sim needs an update; and the update tool shows, applies (idempotently, touching
two values), and refuses an unknown release or a corrupt download without changing the manifest. A new major version is
a larger change: the build's own toolchain (`jdk` in `gradle\libs.versions.toml`, `gradle\gradle-daemon-jvm.properties`)
and `runtime.major` move together.

## What is not in it

- **A JDK.** Downloaded once (the pinned Temurin 25.0.5+8, 134.7 MiB) unless the PC already has exactly that build. The
  release could carry a jlinked runtime instead (about 34 MiB zipped, measured in Gate 5) where downloads are blocked; that
  is a manifest and build change.
- **The Hornet package and any team's code.** A team's project is imported on the PC (`project-import`), built with the
  team's own Gradle build on a JDK 17 to 21 (not the one FTC-Sim runs on), and prepared into
  `%LOCALAPPDATA%\FTC-Sim\programs`. Only the robot profile `hornet-2026.json` is bundled, in the simulator's jar.
- **The Android SDK and the FTC SDK jars.** Importing needs an Android SDK on the PC (found by `AndroidSdkDiscovery`);
  the FTC SDK comes with the team's project.
- **An installer, a signature, automatic updates.** A zip from the internet carries the
  Mark of the Web: Windows may ask once before running `FTC-Sim.cmd` (Properties, Unblock, before unpacking, avoids it).

## Not verified

A clean PC (new Windows install, no Java, no Visual C++ runtime) with an internet connection; a school or company
policy that blocks PowerShell, `cmd.exe`, programs in `%LOCALAPPDATA%` or the downloads; the double-click console
behaviour with Explorer itself (the wrapper's detection and the detach are tested with `cmd /c` and a stand-in
program, not with Explorer and a real window); a PC without a GPU driver that supports OpenGL, which the self test
deliberately does not open; the licence notices by a person who knows the licences (they are generated from the
libraries' own metadata and checked for consistency, see above), and a build with no network for a library whose POM is
not yet in the Gradle cache.
