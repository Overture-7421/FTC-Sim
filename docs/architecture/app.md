# The application: the composition root

Module `app`, package `dev.ftcsim.app`. It is what a user runs, and the one place that knows where things are on the
computer. `desktop-ui` knows nothing of processes, JDKs or Gradle: it gets an `AppHost` and uses what the host gives
it. `app` builds that host from the parts that already exist and starts the window on it.

```
Main  ->  dev.ftcsim.ui.App.run(RealHost, args)               the window (desktop-ui)
              |
RealHost implements AppHost
   openLink()  ->  WorkerLauncher (sim-link)  ->  worker process   (Java 25, the same Java as this program)
   preparer()  ->  ImporterPreparer           ->  ProjectImporter (project-import), on a JDK 17 to 21
   mappings()  ->  ProjectMappingsImpl                              the project's ftcsim.json
```

`checkModuleRules` allows `app` -> `sim-contracts`, `sim-link`, `desktop-ui`, `project-import`. The worker and the
robot world are reached as processes and as files, not as code (see "Where the worker's jars are").

## Two Javas

The simulator (window and worker) runs on the Java 25 that comes with the distribution (`runtime\`, a jlink image;
`distribution.md`), in development on Gradle's toolchain JDK 25; the worker is started on `<java.home>\bin\java.exe` of
the application, because a worker on another JVM would be another simulator. Importing a source project runs the
project's own Gradle build, which a 2024 project cannot run on Java 25, so `ImporterPreparer` finds a **JDK 17 to 21** of
the user's (21 preferred, then 17, 20, 19, 18) at `JAVA_HOME` and the places of the resource `jdk-patterns.txt`
(Adoptium, Microsoft, Corretto, Zulu, `.gradle\jdks`, Android Studio's bundled JDK and so on). A JDK is judged from its
`release` file and `bin\java.exe` and `bin\javac.exe`; nothing is run, and neither the registry nor `PATH` is searched.
With none, the failure is `NO_JDK`, saying that Android Studio includes a suitable JDK or to install Temurin 21, with a
line for each JDK found and why it cannot be used.

## What it needs on disk

An installation is built by `:app:dist` (layout: `distribution.md`). The application finds nothing else by looking: no
`PATH`, no registry, no `JAVA_HOME` for itself. During development `:app:run` starts it from the Gradle build with the
same files in Gradle's own folders.

## Folders under `%LOCALAPPDATA%\FTC-Sim`

`AppFolders` is the only place that names them. All are per user, made on demand, and none holds anything that cannot
be made again except the logs. The user interface keeps its own files in the same folder (`config\`, `projects\` and so
on); they are not `app`'s.

| Folder | What it is for |
|---|---|
| `natives\` | The physics engine's native library, unpacked by the worker once per version (the worker's own default is this folder, so there is one cache). |
| `logs\` | `app-<time>.log` per start of the application (20 newest kept), `worker-<time>-<id>.log` per worker launch (all but the newest 10 deleted when a worker starts; the same lines reach the window's console as `Log` events, `link.md`), and the Java runtime's crash reports. |
| `import\work\<name>-<hash>\` | The importer's private copy of one team project and what its build makes there. Named by the first 12 characters of the project folder's name and 8 hex digits of the hash of its full path, so the same project always gets the same workspace (its Gradle build resumes) and two never share one. Short because Windows limits a path to 260 characters and the Android build nests files deeply. |
| `import\work\<name>-<hash>\programs.txt` | The programs made from this project, oldest first, which pruning works from. |
| `programs\<name>-<12 hex>\` | A built program: the project's name and the first 12 hex digits of the hash of all its source and build files. Same source, same folder. |
| `programs\<name>-<12 hex>.build.log` | The complete output of the build that made it, beside the folder because a program's folder holds only what its manifest lists. |
| `programs\.making-<hex>\`, `.discarding-<hex>\` | A program being made (moved to its place only when complete, removed if the build fails) and an old one being thrown away. |
| `profiles\<name>-<hash>.json` | FTC-Sim's own copy of a project's `ftcsim.json`, only for a project whose folder cannot be written to. |

### Which programs are kept

Restart code builds again whenever the source changed, so a team that restarts all day would leave one program, a few
hundred megabytes, per edit. After every build the preparer keeps the **two newest** programs of a project (the one just
built or found, and the one before it, which a session may still have open) and deletes the others with their build
logs. Only programs that the project's `programs.txt` names are removed. Windows refuses to move a folder with open
files, so a program a session still has open stays on the list for the next time; one that can be moved is renamed to
`.discarding-<hex>` first, so a crash half way leaves a recognisable leftover. Deleting a folder under `programs` by
hand is always safe; to build a project again although its source is unchanged, delete its folder.

### The project's robot file

`ProjectMappingsImpl` reads and writes `ftcsim.json` at the root of a project, the robot mapping that a team shares
through its repository (format: `docs/robots/fixed-robots.md`). It is the only file FTC-Sim ever adds to a project, and
the importer ignores it (not part of the source hash, the snapshot or the unchanged-original check), so writing it never
makes a rebuild. The file is FTC-Sim's own (schema 2), read and written **whole** with Jackson: no section splicing and no
promise to keep a person's formatting.

- `find` looks for the file in the project, else the application's own copy in `profiles\`, and returns the mapping and
  the `run` defaults. Every key but `schema` is optional and takes the `RobotMapping.empty` value (the robot's name is
  the project folder's); a key that is not part of the file, a value of the wrong type, a motor of no slot or a duplicate
  key is refused. A refused file is still found, with a `problem` ("ftcsim.json is from an older version" for schema 1,
  "from a newer version", or "cannot be read: why") and no mapping; the window treats the project as having none.
- `save` writes the whole mapping, keeping the file's `run` block, through a temporary file and a move. A file found with
  a problem is first copied to `ftcsim.old.json` next to it. A project with no file gets one at its root; when the
  folder cannot be written to, the file goes to `profiles\`, the answer says so, and from then on that copy is the
  project's file. A number that is not finite is refused.
- `saveRunDefaults` rewrites the file with the mapping it holds and the new `run`; it refuses a file that cannot be read.

## Where the worker's jars are: one file, in development and in an installation

The worker is a process, so its jars are not on the application's class path, and the importer's compatibility check
needs the robot world's jars (the Android shim and the SDK adapters). Both lists are in one file,
`ftcsim-classpath.properties` (`worker=` and `robot=` lines, in the format of `dev.ftcsim.link.ClasspathFile`; relative
entries are relative to the file, so an installation can be moved). `AppClasspath.locate()` reads the file named by the
system property `ftcsim.classpathFile`, otherwise the one next to the jar the application runs from; a missing or
incomplete file is an error that says to install again. In development `:app:writeClasspath` writes it into
`app\build\classpath\` with absolute paths and `:app:run` and `:app:test` name it; the distribution task writes the same
file with entries relative to `lib\`.

`app` must not link against the worker, so the build holds the jars in two configurations that nothing extends:
`workerRuntime` (`project(":worker")`) and `robotWorld` (`project(":sdk-common")`), resolvable and not consumable. The
module rules allow `"worker@workerRuntime"` only while it stays isolated; the same dependency in `implementation` or a
configuration that extends it is a violation ("app must not depend on :worker").

## Opening the link

`RealHost.openLink()` starts the worker through `WorkerLauncher` with the jars of the `worker` line, the robot world's
jars as `-Dftcsim.simulatorClasspath`, natives in `natives\` and logs in `logs\`, the options the worker's launcher always
sets (native access, Unsafe, UTF-8, crash report file, private storage folder) and, from the application,
`-Duser.language=en -Duser.country=US -Duser.timezone=UTC` (a robot program formats numbers and dates with the default
locale, and what it prints must not depend on the computer's regional settings) and `-Djava.awt.headless=true`. No heap
size: the JVM's default (a quarter of the memory) has been enough. Whatever goes wrong is an `IOException` whose message
the window shows as it is: the class path file missing or incomplete, the Java runtime damaged, or the worker not
starting (with what it wrote, and the log folder).

## Preparing a project

`RealHost.preparer()` is one `ImporterPreparer`, so only one project is prepared at a time (a second request fails at
once with `BUSY`). The window calls this "building". Events reach the listener from one thread of the preparer's own, in
order, ending with exactly one of `finished` and `failed`.

**`inspect(folder)`** reads and runs nothing: a folder with `settings.gradle(.kts)` and
`gradle\wrapper\gradle-wrapper.jar` is a `PROJECT`; one that `PreparedProgram.open` accepts is a `PREPARED_PROGRAM` (which
the window does not open but explains); anything else is `NOT_RECOGNISED` with one sentence saying why and what to choose
instead.

**`prepare(request, listener)`** runs on a thread of its own, in order:

1. The folder must be a `PROJECT` (else `NOT_A_GRADLE_PROJECT`, before anything is read: a wrong folder is never hashed).
2. **Cache.** All source and build files are hashed (`SourceManifest`). If `programs\<name>-<12 hex>` exists, opens, and
   its manifest records the same full fingerprint, it is returned at once (`Already prepared, source unchanged`), with no
   trust, JDK or Android SDK needed. A damaged one is made again.
3. The JDK for the project's build (see "Two Javas") and the Android SDK: the first existing folder of `ANDROID_HOME`,
   `ANDROID_SDK_ROOT` and `%LOCALAPPDATA%\Android\Sdk`; if none exists the importer's own `NO_ANDROID_SDK` says so.
4. The importer runs with `TRUST_BUILD_SCRIPTS` (choosing the folder is the consent; `prepared-program.md`),
   a fresh `programs\.making-<hex>` as the package folder and the robot world's jars as `simulatorClasspath`. **The
   project folder is only read**: the importer builds in a copy and verifies by hash that the original is unchanged.
5. On success the program moves to `programs\<name>-<12 hex>`, the build log is copied beside it, and `Prepared` says the
   program's folder, the project's name, the SDK version, the OpMode names found in the bytecode, and the importer's
   findings each as a sentence naming class and method (`org.team.Drive.run(): Creates a java.lang.Thread.`).

**Failures** carry a `code`: one of the importer's (`ImportException.failure()`) or the application's own
`NOT_A_GRADLE_PROJECT`, `NO_JDK`, `INCOMPLETE_INSTALL` (the class path file or the list of JDK places is missing),
`BUSY`, `IO_ERROR` (a leftover program in use cannot be replaced) and `INTERNAL` (an unexpected exception, with its first
lines of stack trace). `Job.cancel()` ends with `CANCELLED` at the importer's next safe point.

## Running it

```
.\gradlew.bat :app:run                       FTC-Sim, on the toolchain's JDK 25 with the options of src\dist\runtime-options
.\gradlew.bat :app:run --args="--exit-after=30 --no-focus"
```

`ftc-sim: run app` and `Debug app (Gradle run with --debug-jvm)` in VS Code do the same (`docs/vscode.md`). The options
after `--args` are the window's. What the program prints is also written to `logs\app-<time>.log` (`AppLog`), in
development as in an installation. `--self-test` (`SelfTest`) checks an installation without a window
(`distribution.md`). What `app` tests, and the Hornet-gated tests of the real host, are in `docs/testing.md`.

**Measured on Hornet** (FTC SDK 11.1.0, Android Studio's JDK 21, a warm Gradle cache): preparing the unmodified checkout
took 19 s and found 11 OpModes (the SDK host lists a 12th, which a library registers in code) and 6 findings; preparing it
again took 0.4 s; a session on the package in the real worker reached Init and Running in about 5 s.

**Why Restart code starts a new worker** (`RealHostProjectTest`, Hornet's DECODE session opened again and again, after a
full garbage collection on the worker): opening a session **in the same worker** left 59 MB of heap in use after the first
open and 214 MB after the eighth (about 20 MB more for every world opened), and the worker's memory grew from 333 MB to
1,052 MB. Opening it each time on a **fresh worker** gave 56 MB, 57 MB and 56 MB, and about 335 MB each time. The old worlds are
not freed by the worker, so `AppServices.openSession` asks for a session that replaces another on a link made anew and closes
the old worker (a few seconds longer than reopening in place); the first session of a link is opened on it. The cause of the
retention in the worker was not looked for.

## Not done

- A program that a session still has open is not pruned until a later build.
- When the worker dies the link is finished; a new `openLink()` starts another, and the window decides when.
- No test opens the window on this host. `Main` was run once by hand through `:app:run` with `--exit-after=12 --no-focus`:
  the worker started when the window opened and ended when it closed, and nothing was left running.
