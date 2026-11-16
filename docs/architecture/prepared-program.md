# Importing a team project and the prepared program

Module `project-import`, package `dev.ftcsim.importer`. Windows only, like the rest of FTC-Sim.

## What it does

An **import** turns an unmodified FTC team project (Gradle, Android Studio layout) into a **prepared program**: a
folder, optionally zipped, that the simulator runs without Android Studio, the Android SDK, Gradle, Git or a
compiler. Only the import needs those. (The window calls an import a *build* and keeps the programs itself: the person
opens a project and never handles a prepared program, `docs/architecture/app.md`.)

`ProjectImporter.importProject(ImportRequest, ImportProgress)` runs these stages, which a UI can follow through
`ImportProgress` (`stage`, `message` for each line of Gradle's output, and `isCancelled`, which is polled and stops
the build and removes the unfinished package):

1. **SNAPSHOT.** The project is copied into the caller's workspace. Build outputs (`build` next to a Gradle script),
   `.git`, `.gradle`, IDE folders, `local.properties` and `*.iml` are not copied, and neither is the robot description
   `ftcsim.json` at the project's root: it is not source of the robot program (the simulator reads it from the project
   itself), so editing it, or the application writing it, is neither a change to the project nor a reason to build again.
   The SHA-256 of every copied
   (source and build) file of the original is recorded; at the end the original is hashed again and the import
   **fails and produces nothing** if a single file differs. All Gradle runs happen in the copy.
2. **GRADLE_EXPORT.** The copy's own Gradle wrapper runs with one init script added on the command line
   (`ftcsim-export.init.gradle`, a resource of this module). The project's repositories, variants and dependency
   rules apply as they do in Android Studio. The script reports the compiled classes, the resolved runtime class
   path (AARs' `classes.jar` and `libs/*.jar` merged, plain jars), the AARs, the **Java resources** of every library
   and of the project (see below), the resources and assets of the project's own modules, and the
   `org.firstinspires.ftc` artifacts, from which the **FTC SDK version** is read.
   `ANDROID_HOME` and `JAVA_HOME` are passed in the child process's environment; nothing is written to the project
   (the original's `local.properties` is never read or copied, and the copy gets none).
3. **RESOURCES.** Android resources are generated without aapt (see below).
4. **PACKAGE, ANALYSIS.** Files are laid out, the Java resources are merged, the OpModes are found and the pre-flight
   runs, both by reading the bytecode with ASM (no class is loaded), and the manifest is written.
5. **VERIFY.** The package is opened exactly as a driver's machine opens it, the original is hashed again, and the
   zip is written if one was asked for.

The JDK home and the Android SDK folder are parameters. Finding them is the application's job (`app.md`); the
distribution brings its own Java for the simulator, so installing a JDK is nobody's.

## Trust: a child process is not a sandbox

Importing from source **executes the project's build scripts**, and the plugins and wrapper they name, on this
machine with the current user's rights. They run in a separate Gradle process, which is **not a sandbox**: it limits
nothing that the scripts can read, write or send. Every `ImportRequest` therefore carries a `BuildTrust`, there is no
default, and nothing starts unless the caller passes `TRUST_BUILD_SCRIPTS` after telling the user (the application takes the person's choice of the project folder as that decision). Opening a prepared
package runs no build script and needs no such decision.

## Running offline

Gradle needs its dependencies once: an import with network access fills the Gradle cache. A later import with
`withOffline(true)` and the same `withGradleUserHome` runs without a network. A cold cache with `offline` is reported
as `OFFLINE_CACHE_COLD`. A package, once made, needs no network and no Gradle at all.

## Failures

`ImportException.failure()` is one of `NOT_TRUSTED`, `INVALID_REQUEST`, `ORIGINAL_CHANGED`, `NOT_A_GRADLE_PROJECT`,
`NO_ANDROID_SDK`, `ANDROID_SDK_INCOMPLETE`, `LICENSES_NOT_ACCEPTED`, `WRONG_JDK`, `OFFLINE_CACHE_COLD`,
`NETWORK_UNAVAILABLE`, `DEPENDENCY_RESOLUTION`, `COMPILATION_FAILED`, `NO_APPLICATION_MODULE`, `NOT_AN_FTC_PROJECT`,
`OUT_OF_MEMORY`, `TIMED_OUT`, `BUILD_FAILED`, `RESOURCES_FAILED`, `IO_ERROR` or `CANCELLED`. The message says what to
do; the lines of Gradle's output that explain it are in `diagnostics()` and the complete output is in `logFile()`. The
classifier was written against real Hornet/Gradle 8.9 output for the Android SDK, licence, offline and dependency
cases; the wrong-JDK, compilation, network and memory cases are matched on the messages Gradle and the Android
plugin are known to print, and have not been seen on a real project.

## The prepared program

```
manifest.json                     the manifest; the one file it does not list
program/<module>.jar              the team's compiled code
libs/<nnn>-<library>.jar          the libraries, numbered in class path order
resources/r.jar                   generated Android R classes (bytecode)
resources/ftcsim-resources.tsv    strings, integers, booleans and colours by resource id
resources/assets/...              the libraries' and the project's raw assets
resources/java-res.jar            the libraries' and the project's Java resources, merged (absent when there are none)
```

The program is the application module and any other module of the project that declares OpModes; everything else,
including the FTC SDK, is a library jar. The zip (`PackageZip.create`) holds the same entries in name order with fixed
timestamps, so the same inputs give the same bytes; `PackageZip.extract` writes only manifest-listed files, caps each
at its declared size, rejects unsafe names and returns the opened package.

### manifest.json (format `ftcsim-prepared-program`, version 2)

| Property | Content |
| --- | --- |
| `format`, `formatVersion` | `ftcsim-prepared-program`, `2` (version 1 is still read, see below) |
| `importerRevision` | which revision of the importer made the package; see "Importer revision" |
| `project` | `name`, `applicationModule` (`:TeamCode`), `namespace`, `sourceFingerprint` (SHA-256 over the original's file hashes), `sourceFiles` |
| `sdk` | `version` (`11.1.0`), `components` (`group:name:version`) |
| `program` | jar paths of the team's code |
| `libraries` | `[{path, id}]` in class path order |
| `resources` | `classes` (the R jar), `directory` (the folder with the table and assets), `javaResources` (the jar of Java resources, or `""` for none) |
| `opModes` | `count`, `list` of `{name, group, kind, className}` from the bytecode scan |
| `warnings` | `[{code, subject, member, detail}]`, the compatibility pointers below |
| `files` | `[{path, sha256, size}]` for every file but the manifest |
| `extras` | an object for what later parts of the simulator attach (robot profile, field references); the importer writes `{}` and keeps what it finds |

There are no timestamps, so equal inputs give equal manifests. JSON is read strictly (Jackson, in
`dev.ftcsim.importer.manifest` only): unknown properties, missing properties, nulls, duplicate keys, trailing text and
wrong types are errors, and nothing has a silent default. `PreparedProgram.open(directory)` rejects, with the
property or file named: a different format; a version newer than the reader's ("made by a newer FTC-Sim, so update");
unsafe or duplicate paths; a listed file (the Java resources jar included) that is missing, has another size or
another SHA-256; and any file in the folder that the manifest does not list.

**Version 1** is what importer revision 1 wrote: it has neither `importerRevision` nor `resources.javaResources`.
It is still read, as a version 2 manifest of importer revision 1 without Java resources (so rewriting it, as later parts
of the simulator do to add `extras`, writes version 2). A version 1 manifest that has a version 2 property is refused.

### Importer revision

`ProjectImporter.IMPORTER_REVISION` (2) is written into every package and read back by
`PreparedProgram.importerRevision()`. It is raised whenever a package would come out different or better, so a program
that keeps packages (the acceptance tests keep Hornet's in `%LOCALAPPDATA%\FTC-Sim\hornet-package`) can tell a stale one
and prepare the program again; `HornetFixture` does. Revision 1 left the Java resources of the libraries out; revision 2
puts them in. A package of an older revision still opens, and `warnings()` then has one more finding,
`JAVA_RESOURCES_MISSING`, that is made on reading and is not part of what the package recorded.

### Using a package

```java
PreparedProgram program = PreparedProgram.open(directory);          // verifies structure and every hash
List<Path> robot = new ArrayList<>(simulatorJars);                  // android-shim, sdk-common, robot-runtime
robot.addAll(program.robotClasspath());                             // program, R jar, resources folder, libraries, Java resources
RobotProgram run = new RobotProgram(robot, program.applicationClasspath(), "description");
```

`programClasspath()` is the team's jars and `libraryClasspath()` the R jar, the resources folder, the libraries and
last the Java resources jar. The simulator's own jars are never in the package. **The SDK is given
`applicationClasspath()` (program, then the library jars, then the Java resources jar) as the application's code**, not
the program alone: the SDK, and libraries such as Panels, scan the whole installed application for OpModes, hooks and
annotated settings.

## Java resources

The Android Gradle plugin (AGP) hands the compiler, and the importer's `android-classes-jar` view, **class jars that
have every non-class file taken out**: images, `META-INF/services` provider files, properties and the like. On a device
AGP merges those files into the APK in `mergeDebugJavaResource`, so `ClassLoader.getResourceAsStream("decode-dark.png")`
(Panels' field images) and `ServiceLoader` work. A package of importer revision 1 had only the stripped jars, so
Panels died at start-up with `FileNotFoundException: Resource not found: decode-dark.png`.

The init script therefore also exports the artifact view `android-java-res` of the application's `debugRuntimeClasspath`,
in class path order: an AAR's `classes.jar` and `libs/*.jar` as published (resources included), a plain jar whole, and the
processed `java_res` folder of each project module; the application's own `src/main/resources` come from its
`processDebugJavaRes` task. The records are trailing fields of the `application` and `library` export records, which an
older reader ignores. Plain jars are also copied whole into `libs/`, as before.

`resources/java-res.jar` is one deterministic jar (sorted entries, one time stamp) of the files from all of these,
merged by `JavaResourceMerger` with the rules AGP 8.7.0 applies to an application (read from its classes:
`PackagingOptionsUtilsKt`, `ParsedPackagingOptions`, `MergeJavaResourceTask`, `ResourcesApkPackagingImpl`,
`StreamMergeAlgorithms`, `CombinedInputStream`):

- Not Java resources: files ending in `.class` or `.so`.
- Excluded (the 31 `defaultExcludes`, tested as globs against `"/" + entry name`): `/META-INF/LICENSE`,
  `/META-INF/LICENSE.txt`, `/META-INF/MANIFEST.MF`, `/META-INF/NOTICE`, `/META-INF/NOTICE.txt`, `/META-INF/*.DSA`,
  `/META-INF/*.EC`, `/META-INF/*.SF`, `/META-INF/*.RSA`, `/META-INF/maven/**`, `/META-INF/proguard/*`,
  `/META-INF/com.android.tools/**`, `/NOTICE`, `/NOTICE.txt`, `/LICENSE.txt`, `/LICENSE`, `**/.svn/**`, `**/CVS/**`,
  `**/SCCS/**`, `**/.*/**`, `**/.*`, `**/*~`, `**/thumbs.db`, `**/picasa.ini`, `**/about.html`, `**/package.html`,
  `**/overview.html`, `**/protobuf.meta`, `**/_*`, `**/_*/**`, `**/*.kotlin_metadata`; and, for an APK only,
  `/META-INF/*.kotlin_module`.
- Merged (`defaultMerges`): `/META-INF/services/**` and `jacoco-agent.properties`. The files of one name are joined in
  order, with a line feed between two of them unless the first ends with one (as AGP's `CombinedInputStream` does).
- Every other file: the first source wins. AGP stops the build when two sources hold such a file with different
  contents unless the project names it in `pickFirsts`; the importer keeps the first and adds a
  `JAVA_RESOURCE_CONFLICT` warning that names the file and the sources. Identical copies are not a conflict.
- Order, as AGP's priorities: the application's own resources, then the project's modules, then the libraries, each in
  class path order.
- Not mirrored: the project's own `packagingOptions` (`excludes`, `pickFirsts`, `merges`) are not read. Hornet has only
  `pickFirst '**/*.so'`, which does not concern Java resources.

Check against AGP itself: for Hornet, `mergeDebugJavaResource`'s `base.jar` and the package's `java-res.jar` hold the same
71 files with the same SHA-256 (3.2 MB, mostly R8's `new_api_database.ser`, which is on Hornet's runtime class path).
Hornet's `decode-dark.png` (140,956 bytes) and `decode-light.png` (80,959 bytes) are among them, and
`HornetImportTest` and `HornetPackageTest` load both through a class loader over the package's robot class path.

## OpModes: found in class files, and registered in code

The scan applies the rules of the SDK's own `AnnotatedOpModeClassFilter`: a public class that extends `OpMode`
(through the class path's hierarchy) and has `@TeleOp` or `@Autonomous` (not both), is not a non-static inner class,
has a legal name, and is not `@Disabled`. Same-named OpModes are told apart as the SDK does. The SDK host's list is
the authority once a robot world runs.

OpModes that code registers when the program starts (`@OpModeRegistrar` methods, which libraries such as Panels use)
cannot be found by reading class files. The import points at those methods with `OPMODE_REGISTRAR` warnings (the
SDK's own registrars are left out), so a host list longer than `opModes.list` is explained. On Hornet the scan finds
11 and the real SDK host lists 12: the extra one is Panels' "Enable/Disable Panels".

## Compatibility pre-flight

Static and cheap; each warning gives a class and, where it applies, a method. They are pointers, **not claims that
anything fails at run time**.

| Code | Meaning |
| --- | --- |
| `ANDROID_CLASS_MISSING`, `ANDROID_MEMBER_MISSING` | the team's code uses `android.*` or `dalvik.*` classes, or methods and fields of them, that the simulator's shim lacks (checked against the jars given with `ImportRequest.withSimulatorClasspath`) |
| `CLASS_NOT_PROVIDED` | a class the code uses that is on no entry of the program's class path, in the JDK, or in the shim |
| `NATIVE_LIBRARY_LOAD` | a call to `System.loadLibrary` or `System.load` |
| `NATIVE_LIBRARY_PACKAGED` | an AAR or project module with `.so` files for Android, which a desktop JVM cannot load |
| `THREAD_USE`, `NETWORK_USE` | code that creates or starts threads, timers, executors or pools; code that creates sockets or an `HttpClient`, or opens a `URL` |
| `ANDROID_CHECK_SKIPPED` | no shim was given, so the Android classes were not checked |
| `OPMODE_REJECTED`, `OPMODE_DUPLICATE_NAME`, `OPMODE_REGISTRAR` | see OpModes above |
| `JAVA_RESOURCE_CONFLICT` | a Java resource that several libraries ship with different contents; the first on the class path is used (see Java resources) |
| `JAVA_RESOURCES_MISSING` | made when a package of importer revision 1 is opened: it has no Java resources; prepare the program again |
| `SDK_VERSION_MIXED`, `LINK_SKIPPED`, `TRUNCATED` | the SDK artifacts have different versions; a link in the project was not followed; more findings than the manifest keeps (5000) |

Hornet gives four `NATIVE_LIBRARY_PACKAGED` (RobotCore, EasyOpenCV, AprilTag, OpenCV) and two `OPMODE_REGISTRAR`
(Panels and OpenCV's loader), no Java resource conflict, and nothing against the real shim.

## Android resources without aapt

`dev.ftcsim.importer.resources.ResourceBuilder` reads each AAR's `R.txt` and `res/values` and the project modules'
own `res` folders, and writes `r.jar`, `ftcsim-resources.tsv` and `assets/`:

- Ids are `0x7f000000 | (type + 1) << 16 | entry`, with types and names sorted, so the same inputs give the same ids
  and the same bytes. Each package gets an R class; styleable arrays hold the global attribute ids.
- The R classes are written as bytecode with ASM (static ints and int arrays), so no compiler is needed.
- The table has the values of strings, integers, booleans and colours (references resolved; later libraries
  override earlier ones, the application last). Other resource types get ids but no row. The Android shim's
  `Resources` reads this file and its `AssetManager` reads `assets/`.
- A project module's R class holds every resource of the app (a superset), as when R classes are transitive.
- Not generated: the styleable arrays of the team's own `res` folders (those of libraries come from their `R.txt`).

`acceptance-tests` runs the same code as a `JavaExec` (`GenerateResources <output folder> <file that lists AARs>`)
instead of the former Kotlin task, so the tests and the importer cannot differ. The output replaces its jar only when
the bytes differ, because Windows will not replace a jar another process has open.

## Verified on Hornet

`HornetImportTest` (module `project-import`) and `HornetPackageTest` (module `acceptance-tests`) import the pinned
Hornet project (FTC SDK 11.1.0, AGP 8.7.0, Pedro Pathing, SolversLib, FullPanels) from its unmodified source with a
JDK 21, assert the hashes of the original before and after, validate the package and its zip (a changed byte is
reported by file name), find 11 OpModes, SDK 11.1.0, the Pedro, SolversLib and Panels jars and the Java resources
(both field images through a class loader), and boot the real SDK host on the package class path. They are slow and
need the Android SDK, so they run only with
`-Pftcsim.hornet=<path to the Hornet checkout>` (optionally `-Pftcsim.jdk=<JDK home>` and
`-Pftcsim.androidSdk=<folder>`) and print how to enable them otherwise. A warm import took 18 to 28 s, and the SDK host
booted on the package and listed its OpModes in about 2.5 s.

## Not done

- Licence notices of the libraries are not collected; the manifest records their coordinates only.
- Installing a JDK or an Android SDK. Finding them for an import is the application's (`app.md`); the simulator's own
  Java is the runtime that comes with the distribution.
- OpModes registered by code cannot be listed before the program runs.
- A project's own `packagingOptions` are not read: the Java resources are merged with AGP 8.7.0's rules for an
  application, so a project that excludes, picks first or merges other files would get a slightly different set.
