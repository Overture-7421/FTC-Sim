# The application

What `app` (package `dev.ftcsim.app`) does, where it keeps files, and how it starts a worker and prepares a project; read it when you change startup, folders, the Java it uses or the host the window runs on.

`app` is the composition root: what a user runs, and the one place that knows where things are on the computer. `desktop-ui` knows nothing of processes, JDKs or Gradle; it gets an `AppHost` ([ui.md](ui.md)). `app` builds that host from existing parts and starts the window on it.

```
Main  ->  dev.ftcsim.ui.App.run(RealHost, args)               the window (desktop-ui)
RealHost implements AppHost
   openLink()  ->  WorkerLauncher (sim-link)  ->  worker process   (Java 25, the same Java as this program)
   preparer()  ->  ImporterPreparer           ->  ProjectImporter (project-import), on a JDK 17 to 21
```

`checkModuleRules` allows `app` -> `sim-contracts`, `sim-link`, `desktop-ui`, `project-import`. The worker and the robot world are reached as processes and files, not code (see "Where the worker's jars are").

## Two Javas

| Runs | On | Why |
|---|---|---|
| Window and worker | Java 25: the bundled `runtime\` ([distribution.md](distribution.md)); in development Gradle's toolchain JDK 25 | The worker starts on `<java.home>\bin\java.exe` of the application: a worker on another JVM would be another simulator. |
| The project's own Gradle build (import) | A **JDK 17 to 21** of the user's | A 2024 project's build cannot run on Java 25. |

`ImporterPreparer` finds the JDK via `JdkDiscovery`: 21 first, then 17, then 20, 19, 18, from `JAVA_HOME` and the places in the resource `jdk-patterns.txt` (Adoptium, Microsoft, Corretto, Zulu, `.gradle\jdks`, Android Studio's bundled JDK, ...). A JDK is judged from its `release` file, `bin\java.exe` and `bin\javac.exe`; nothing is run, and neither the registry nor `PATH` is searched. With none found the failure is `NO_JDK` (use Android Studio's JDK or install Temurin 21, with a line per JDK found and why it is unusable).

The application itself looks for nothing else: no `PATH`, no registry, no `JAVA_HOME`. `:app:dist` builds an installation (layout: [distribution.md](distribution.md)); `:app:run` starts the same files from Gradle's folders.

## Folders under `%LOCALAPPDATA%\FTC-Sim`

`AppFolders` is the only place that names them. All are per user and made on demand; none holds anything that cannot be rebuilt except the logs. The UI keeps its own files here too (`config\`, `projects\`; [ui.md](ui.md)).

| Folder | Holds |
|---|---|
| `natives\` | The physics library, unpacked by the worker once per version (one shared cache). |
| `logs\` | `app-<time>.log` per start (20 newest kept); `worker-<time>-<id>.log` per worker launch (10 newest kept; the same lines reach the window's console as `Log` events, [link.md](link.md)); the Java runtime's crash reports. |
| `import\work\<name>-<hash>\` | The importer's private copy of one project and its build output. `<name>` is the first 12 characters of the project folder's name, `<hash>` 8 hex digits of the hash of its full path: the same project always gets the same workspace (its Gradle build resumes), two never share one. Short because Windows limits paths to 260 characters and the Android build nests deeply. |
| `import\work\<name>-<hash>\programs.txt` | Programs made from this project, oldest first, for pruning. |
| `programs\<name>-<12 hex>\` | A built program: the project's name plus 12 hex digits of the hash of all its source and build files. Same source, same folder. |
| `programs\<name>-<12 hex>.build.log` | The full build output, beside the folder because a program's folder holds only what its manifest lists. |
| `programs\.making-<hex>\`, `.discarding-<hex>\` | A program being made (moved into place only when complete, removed if the build fails) and an old one being thrown away. |

### Which programs are kept

Restart code rebuilds whenever the source changed, so a team restarting all day would leave one program (a few hundred MB) per edit. After every build the preparer keeps the **two newest** programs of a project (`KEPT_PROGRAMS`: the one just built or found, and the one before, which a session may still have open) and deletes the others with their build logs.

- Only programs named in the project's `programs.txt` are removed.
- Windows refuses to move a folder with open files, so a program a session still has open stays listed for next time. One that can be moved is renamed to `.discarding-<hex>` first, so a crash halfway leaves a recognisable leftover.
- Deleting a folder under `programs` by hand is always safe; delete one to force a rebuild of unchanged source.

## Where the worker's jars are

The worker is a process, so its jars are not on the application's class path, and the importer's compatibility check needs the robot world's jars (Android shim, SDK adapters). Both lists live in one file, `ftcsim-classpath.properties` (`worker=` and `robot=` lines in the format of `dev.ftcsim.link.ClasspathFile`; relative entries are relative to the file, so an installation can move).

- `AppClasspath.locate()` reads the file named by the system property `ftcsim.classpathFile`, else the one next to the application's jar. A missing or incomplete file is an error telling the user to install again.
- Development: `:app:writeClasspath` writes it into `app\build\classpath\` with absolute paths; `:app:run` and `:app:test` name it. The distribution task writes the same file with entries relative to `lib\`.
- **`app` must not link against the worker.** The build holds the jars in two configurations that nothing extends, `workerRuntime` (`project(":worker")`) and `robotWorld` (`project(":sdk-common")`), resolvable and not consumable. The module rules allow `"worker@workerRuntime"` only while it stays isolated; the same dependency in `implementation` or an extending configuration is a violation ("app must not depend on :worker").

## Opening the link

`RealHost.openLink()` starts the worker through `WorkerLauncher` with:

- the `worker` jars, the robot world's jars as `-Dftcsim.simulatorClasspath`, natives in `natives\`, logs in `logs\`;
- the launcher's own options (native access, Unsafe, UTF-8, crash report file, private storage folder);
- from the application: `-Duser.language=en -Duser.country=US -Duser.timezone=UTC` (a robot program formats numbers and dates with the default locale; its output must not depend on the PC's regional settings) and `-Djava.awt.headless=true`;
- no heap size: the JVM default (a quarter of memory) has been enough.

Failures are an `IOException` whose message the window shows as is: class path file missing or incomplete, Java runtime damaged, or the worker not starting (with what it wrote and the log folder).

## Preparing a project

The window calls this "building". `RealHost.preparer()` is one `ImporterPreparer`, so one project is prepared at a time (a second request fails at once with `BUSY`). Events reach the listener in order from the preparer's own thread and end with exactly one of `finished` and `failed`.

**`inspect(folder)`** reads and runs nothing. `settings.gradle(.kts)` plus `gradle\wrapper\gradle-wrapper.jar` is a `PROJECT`; a folder `PreparedProgram.open` accepts is a `PREPARED_PROGRAM` (the window explains it, does not open it); anything else is `NOT_RECOGNISED` with one sentence on why and what to choose.

**`prepare(request, listener)`** runs on its own thread, in order:

1. The folder must be a `PROJECT`, else `NOT_A_GRADLE_PROJECT` before anything is read (a wrong folder is never hashed).
2. **Cache.** All source and build files are hashed (`SourceManifest`). If `programs\<name>-<12 hex>` exists, opens, and its manifest records the same full fingerprint, it is returned at once ("Already prepared, source unchanged") with no trust, JDK or Android SDK needed. A damaged one is made again.
3. The JDK for the project's build (above) and the Android SDK: the first existing of `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `%LOCALAPPDATA%\Android\Sdk`; none gives the importer's `NO_ANDROID_SDK`.
4. The importer runs with `TRUST_BUILD_SCRIPTS` (choosing the folder is the consent; [prepared-program.md](prepared-program.md)), a fresh `programs\.making-<hex>` as package folder and the robot world's jars as `simulatorClasspath`. **The project folder is only read**: the importer builds in a copy and verifies by hash that the original is unchanged.
5. On success the program moves to `programs\<name>-<12 hex>`, the build log is copied beside it, and `Prepared` reports the program's folder, the project's name, the SDK version, the OpMode names found in bytecode, and the importer's findings, each a sentence naming class and method (`org.team.Drive.run(): Creates a java.lang.Thread.`).

**Failures** carry a `code`: an importer one (`ImportException.failure()`) or the application's own `NOT_A_GRADLE_PROJECT`, `NO_JDK`, `INCOMPLETE_INSTALL` (class path file or JDK-places list missing), `BUSY`, `IO_ERROR` (a leftover program in use cannot be replaced) or `INTERNAL` (unexpected exception, first lines of its stack trace). `Job.cancel()` ends with `CANCELLED` at the importer's next safe point.

## Running it

```
.\gradlew.bat :app:run                       FTC-Sim on the toolchain's JDK 25 with the options of src\dist\runtime-options
.\gradlew.bat :app:run --args="--exit-after=30 --no-focus"      options after --args are the window's
```

VS Code: `ftc-sim: run app` and `Debug app (Gradle run with --debug-jvm)` (`docs/vscode.md`). Output is also written to `logs\app-<time>.log` (`AppLog`), in development as in an installation. `--self-test` ([distribution.md](distribution.md)) checks an installation without a window. Tests of `app` and the Hornet-gated real-host tests: `docs/testing.md`.

**Measured on Hornet** (FTC SDK 11.1.0, Android Studio's JDK 21, warm Gradle cache): preparing the unmodified checkout took 19 s and found 11 OpModes (the SDK host lists a 12th, registered in code) and 6 findings; preparing again took 0.4 s; a session on the package in the real worker reached Init and Running in about 5 s.

## Why Restart code starts a new worker

Measured by `RealHostProjectTest` (Hornet's DECODE session opened repeatedly, after a full GC on the worker):

| Opening | Heap in use after 1st / 8th open | Worker memory |
|---|---|---|
| In the **same** worker | 59 MB / 214 MB (about 20 MB per world) | 333 MB growing to 1,052 MB |
| On a **fresh** worker each time | 56-57 MB every time | about 335 MB every time |

The worker does not free old worlds, so `AppServices.openSession` opens a session that replaces another on a link made anew and closes the old worker (a few seconds longer than reopening in place; a link's first session opens on it as is). The cause of the retention was not investigated.

## Not done

- A program a session still has open is not pruned until a later build.
- When the worker dies the link is finished; a new `openLink()` starts another and the window decides when.
- No test opens the window on this host. `Main` was run once by hand (`:app:run --args="--exit-after=12 --no-focus"`): the worker started with the window, ended when it closed, and nothing was left running.
