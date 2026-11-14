# The application: the composition root

Module `app`, package `dev.ftcsim.app`. It is what a user runs, and the one place that knows where things are on the
computer. `desktop-ui` knows nothing of processes, JDKs or Gradle: it gets an `AppHost` and uses what the host gives
it. `app` builds that host from the parts that already exist and starts the window on it.

```
Main  ->  dev.ftcsim.ui.App.run(RealHost, args)               the window (desktop-ui)
              |
RealHost implements AppHost
   openLink()  ->  WorkerLauncher (sim-link)  ->  worker process   (JDK 25, the same Java as this program)
   preparer()  ->  ImporterPreparer           ->  ProjectImporter (project-import), on a JDK 17 to 21
```

`checkModuleRules` allows `app` -> `sim-contracts`, `sim-link`, `desktop-ui`, `project-import`. The worker and the
robot world are reached as processes and as files, not as code: see "Where the worker's jars are".

## What it needs on disk

An installation (built by `:app:dist`; `docs/architecture/distribution.md` has the full layout):

```
<install>\lib\app.jar and the jars of desktop-ui, project-import, sim-link, sim-contracts and their libraries
<install>\lib\ftcsim-classpath.properties          the worker's jars and the robot world's jars, relative to this file
<install>\lib\worker.jar and every jar the worker needs, sdk-common.jar, android-shim.jar, ...
the Java 25 that runs the application (its java.home is the worker's Java too)
```

The application finds nothing else by looking: no `PATH`, no registry, no `JAVA_HOME` for itself. During development
`:app:run` starts it from the Gradle build with the same files in Gradle's own folders.

## Folders under `%LOCALAPPDATA%\FTC-Sim`

`AppFolders` is the only place that names them. All are per user, made on demand, and none holds anything that cannot
be made again except the logs.

| Folder | What it is for |
|---|---|
| `natives\` | The physics engine's native library, unpacked by the worker once per version. The worker's own default is this folder, so there is one cache. |
| `logs\` | One `worker-<time>-<id>.log` per worker launch (what the worker wrote to its output and error), and the Java runtime's crash report if it crashed. When a worker starts, all but the newest 10 launches are deleted. |
| `import\work\<name>-<hash>\` | The importer's private copy of one team project and what its build makes there. Named by the first 12 characters of the project folder's name and 8 hex digits of the hash of its full path, so the same project always gets the same workspace and its Gradle build resumes, and two projects never share one. Short because Windows limits a path to 260 characters and the Android build nests files deeply. |
| `programs\<name>-<12 hex>\` | A prepared program: the project's name and the first 12 hex digits of the hash of all its source and build files. Same source, same folder. |
| `programs\<name>-<12 hex>.build.log` | The complete output of the build that made it, beside the folder because a program's folder holds only what its manifest lists. |
| `programs\.making-<hex>\` | A program being made; it moves to its place only when complete, and is removed if the preparation fails. |

The user interface keeps its own files in the same `FTC-Sim` folder (`config\`, `projects\` and so on); they are not
`app`'s.

Programs of earlier versions of a project are not deleted: a project that changes often leaves one program per version,
a few hundred megabytes each. Deleting a folder under `programs` is safe, nothing else refers to it but the window's
list of recent runs. To prepare a project again although its source is unchanged, delete its folder under `programs`.

## Where the worker's jars are: one file, in development and in an installation

The worker is a process, so its jars are not on the application's class path, and the importer's compatibility check
needs the jars of the robot world (the Android shim and the SDK adapters). Both lists are in one file,
`ftcsim-classpath.properties`, in the format of `dev.ftcsim.link.ClasspathFile` (also the format of the
`program.properties` that the worker reads):

```
worker=lib\worker.jar;lib\sim-link.jar;...
robot=lib\sdk-common.jar;lib\android-shim.jar;...
```

Entries are separated as a class path is, taken as written (no escapes), and relative ones are relative to the file, so
an installation can be moved as a whole. `AppClasspath.locate()` reads the file named by the system property
`ftcsim.classpathFile`, otherwise the one next to the jar the application runs from; a missing or incomplete file is an
error that says what is missing and to install again. Entries must exist.

- **Development:** `:app:writeClasspath` writes the file into `app\build\classpath\` with absolute paths into Gradle's
  folders, and `:app:run` and `:app:test` name it in `ftcsim.classpathFile`. A test (`AppClasspathTest`) reads that file
  and checks the worker's main class, the simulator and the importer are in the worker's jars and the window and the
  application are not.
- **Installation:** the distribution task writes the same file with entries relative to `lib\`.

`app` must not link against the worker, so the build holds the jars in two configurations that nothing extends:
`workerRuntime` (`project(":worker")`) and `robotWorld` (`project(":sdk-common")`), resolvable and not consumable.
The module rules know this: an entry `"worker@workerRuntime"` allows a project dependency in that one configuration
and only while it stays isolated; the same dependency in `implementation` or a configuration that extends it is a
violation. (Checked by making it one: the build fails with "app must not depend on :worker".)

## Opening the link

`RealHost.openLink()` starts the worker through `WorkerLauncher` with:

- **the Java this program runs on**, `<java.home>\bin\java.exe`. The product is pinned to one JDK and the launcher (in an
  installation) or Gradle's toolchain (in development) guarantees it; a worker on another JVM would be another simulator;
- the jars of the `worker` line, the robot world's jars of the `robot` line as `-Dftcsim.simulatorClasspath`, natives in
  `natives\` and logs in `logs\`;
- what the launcher always sets (`--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow` so that the JDK
  does not warn when the robot world seeds `ThreadLocalRandom`, UTF-8 output, the crash report file, a private
  storage folder), and from the application: `-Duser.language=en -Duser.country=US -Duser.timezone=UTC`, because a robot
  program formats numbers and dates with the default locale and what it prints must not depend on the regional settings
  of the computer, and `-Djava.awt.headless=true`, because the worker has no window. No heap size: the JVM's default
  (a quarter of the memory) has been enough.

Whatever goes wrong is an `IOException` whose message the window shows as it is: the class path file missing or
incomplete, the Java runtime damaged, or the worker not starting (with what the worker wrote, and the log folder).

## Preparing a project

`RealHost.preparer()` is one `ImporterPreparer`, so only one project is prepared at a time (a second request fails
at once with `BUSY`). Events reach the listener from one thread of the preparer's own, in order, ending with exactly one
of `finished` and `failed`.

**`inspect(folder)`** reads and runs nothing: a folder with `settings.gradle(.kts)` and `gradle\wrapper\gradle-wrapper.jar`
is a `PROJECT`; one that `PreparedProgram.open` accepts is a `PREPARED_PROGRAM` (its summary says the SDK version and
the number of OpModes); anything else is `NOT_RECOGNISED` with one sentence that says why and what to choose instead.

**`prepare(request, listener)`** runs on a thread of its own. Step 1 is looking at the project and the computer; steps 2
to 7 are the importer's stages, with its output lines passed on. In order:

1. The folder must be a `PROJECT` (otherwise `NOT_A_GRADLE_PROJECT`, before anything is read of it: a wrong folder
   is never hashed).
2. **Cache.** All source and build files are hashed (`SourceManifest`, the importer's own). If `programs\<name>-<12 hex>`
   exists, opens, and its manifest records the same full fingerprint, it is returned at once with the step
   `Already prepared, source unchanged`. That needs no trust, no JDK and no Android SDK. A damaged one is made again.
3. **The JDK that runs the project's own build**, between 17 and 21, 21 preferred, then 17, then 20, 19, 18. This is
   not the JDK 25 that the simulator runs on. It looks at `JAVA_HOME` and at the folder patterns that the launcher's
   `scripts\jdk-manifest.json` lists under `discovery.directoryPatterns` (Eclipse Adoptium, Java, Microsoft, Corretto,
   Zulu, BellSoft, Semeru, `.jdks`, `.gradle\jdks`, Android Studio's bundled JDK in `Program Files` and
   `%LOCALAPPDATA%`), so they are defined once: the build copies that file into the application as a resource, and
   fails if it is missing. A JDK is judged from the `release` file every JDK has (`JAVA_VERSION`, `OS_ARCH` x86_64, the
   vendor) and from `bin\java.exe` and `bin\javac.exe`; nothing is run. The registry and `PATH`, which the launcher also
   searches, are not. If none qualifies: `NO_JDK`, saying that Android Studio includes a suitable JDK or to install
   Temurin 21, with a line for each JDK that was found and why it cannot be used.
4. **The Android SDK:** the first existing folder of `ANDROID_HOME`, `ANDROID_SDK_ROOT` and
   `%LOCALAPPDATA%\Android\Sdk`; if none exists the first of them is passed on and the importer's own
   `NO_ANDROID_SDK` says that it is missing.
5. The importer runs with `Request.trusted` as its `BuildTrust`, the workspace and a fresh folder
   `programs\.making-<hex>` as the package folder, and the robot world's jars as `simulatorClasspath`. **The project
   folder is only read**: the importer copies it, builds in the copy, and verifies by hash that the original is unchanged.
6. On success the finished program moves to `programs\<name>-<12 hex>` (a damaged leftover of that name is removed
   only now), the build log is copied beside it, and `Prepared` says the program's folder, the project's name, the SDK
   version, the OpMode names the importer found in the bytecode, the importer's findings each as a sentence that names the
   class and method (`org.team.Drive.run(): Creates a java.lang.Thread.`), and the log. OpModes that code registers when
   the program starts cannot be found in class files; the findings point at those methods, and the SDK host's list is the
   authority once a session runs.

**Failures** carry `code`, one of the importer's (`NOT_TRUSTED`, `NO_ANDROID_SDK`, `COMPILATION_FAILED`, `CANCELLED`, ...,
with its message, the lines of the build's output that explain it, and its log) or the application's own:
`NOT_A_GRADLE_PROJECT`, `NO_JDK`, `INCOMPLETE_INSTALL` (the class path file or the JDK manifest is missing), `BUSY`,
`IO_ERROR` (a leftover program that is in use cannot be replaced) and `INTERNAL` (an unexpected exception, with its
first lines of stack trace). `Job.cancel()` ends with `CANCELLED` at the importer's next safe point.

## Running it

```
.\gradlew.bat :app:run                       FTC-Sim, on the project's JDK 25 with --enable-native-access and --sun-misc-unsafe-memory-access
.\gradlew.bat :app:run --args="--exit-after=30 --no-focus"
```

`ftc-sim: run app` and `Debug app (Gradle run with --debug-jvm)` in VS Code do the same (`docs/vscode.md`). The options
after `--args` are the window's.

## Tests

`:app:test`: the discovery of JDKs and Android SDKs on a computer described by a folder and a few variables, including
every location of the launcher's manifest; the folder naming; the class path file and the one Gradle writes;
`inspect`; the preparer with a stand-in for the importer that leaves a real package (steps, mapping, cache hit,
changed source, damaged program replaced only after success, failures, cancel, busy, an unexpected error); the real
importer's refusal of an untrusted project; `RealHost`'s messages; and the real worker started from the generated class
path file (`RealHostWorkerTest`), which connects, tells its catalogue and ends when the link closes.

`:acceptance-tests:test` (tag `two-process`): `RealHostSessionTest` starts the real worker through `RealHost.openLink()`
and drives the fixture robot in it. `RealHostHornetTest` needs a developer's computer and runs only with
`-Pftcsim.hornetPackage=<prepared package>` (a session in the real worker on a team's unmodified program: it opens, the
OpMode reaches Init and Running, simulated time advances, there is telemetry, nothing fails or is refused, the worker
exits cleanly) and with `-Pftcsim.hornet=<checkout>` (the preparer on the checkout: stages, results, the checkout
unchanged, the same source not built twice; optionally `-Pftcsim.jdk=` and `-Pftcsim.androidSdk=`). Without the
properties they say how to enable them.

**Measured on Hornet** (FTC SDK 11.1.0, Android Studio's JDK 21, a warm Gradle cache): preparing the unmodified checkout
took 19 s and found 11 OpModes (the SDK host lists a 12th, which a library registers in code) and 6 findings; preparing it
again took 0.4 s; a session on the package in the real worker reached Init and Running in about 5 s. The worker's error
output held no warning of the Java runtime, with the defaults above.

## Not done

- Programs of earlier versions are not pruned (see above).
- When the worker dies the link is finished; a new `openLink()` is what starts another. The window decides when.
- No test opens the window on this host. `Main` was run once by hand through `:app:run` with `--exit-after=12 --no-focus`:
  the worker started when the window opened and ended when it closed, and nothing was left running.
