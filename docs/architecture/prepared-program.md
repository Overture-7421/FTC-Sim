# Importing a team project and the prepared program

Module `project-import`, package `dev.ftcsim.importer`. Windows only, like the rest of FTC-Sim.

## What it does

An **import** turns an unmodified FTC team project (Gradle, Android Studio layout) into a **prepared program**: a
folder, optionally zipped, that the simulator runs without Android Studio, the Android SDK, Gradle, Git or a
compiler. Only the import needs those. (The window calls an import a *build* and keeps the programs itself; the person
never handles a prepared program, `app.md`.)

`ProjectImporter.importProject(ImportRequest, ImportProgress)` runs these stages, which a UI follows through
`ImportProgress` (`stage`, `message` for each line of Gradle's output, and `isCancelled`, which is polled and stops the
build and removes the unfinished package):

1. **SNAPSHOT.** The project is copied into the caller's workspace, without build outputs, `.git`, `.gradle`, IDE folders,
   `local.properties`, `*.iml` and the robot mapping `ftcsim.json` (not source of the robot program, so editing it is
   never a reason to build again). The SHA-256 of every file of the original is recorded; at the end the original is
   hashed again and the import **fails and produces nothing** if a single file differs. All Gradle runs happen in the copy.
2. **GRADLE_EXPORT.** The copy's own Gradle wrapper runs with one init script added on the command line
   (`ftcsim-export.init.gradle`, a resource of this module), so the project's repositories, variants and dependency rules
   apply as in Android Studio. The script reports the compiled classes, the resolved runtime class path, the AARs, the
   Java resources (below), the project's own resources and assets, and the `org.firstinspires.ftc` artifacts, from which
   the **FTC SDK version** is read. `ANDROID_HOME` and `JAVA_HOME` go in the child process's environment; nothing is
   written to the project.
3. **RESOURCES.** Android resources are generated without aapt (below).
4. **PACKAGE, ANALYSIS.** Files are laid out, the Java resources merged, the OpModes found and the pre-flight run, both
   by reading bytecode with ASM (no class is loaded), and the manifest written.
5. **VERIFY.** The package is opened exactly as a driver's machine opens it, the original is hashed again, and the zip is
   written if one was asked for.

The JDK home and the Android SDK folder are parameters; finding them is the application's job (`app.md`).

## Trust: a child process is not a sandbox

Importing from source **executes the project's build scripts**, and the plugins and wrapper they name, on this
machine with the current user's rights, in a separate Gradle process that is **not a sandbox**: it limits nothing the
scripts can read, write or send. Every `ImportRequest` therefore carries a `BuildTrust`, there is no default, and nothing
starts unless the caller passes `TRUST_BUILD_SCRIPTS` after telling the user (the application takes the person's choice
of the project folder as that decision). Opening a prepared package runs no build script and needs no such decision.

## Running offline, and failures

An import with network access fills the Gradle cache; a later one with `withOffline(true)` and the same
`withGradleUserHome` runs without a network (a cold cache is `OFFLINE_CACHE_COLD`). A package, once made, needs no
network and no Gradle at all.

`ImportException.failure()` names the cause (the enum has the list); the message says what to do, the lines of Gradle's
output that explain it are in `diagnostics()` and the complete output in `logFile()`. The classifier was written against
real Hornet/Gradle 8.9 output for the Android SDK, licence, offline and dependency cases; the wrong-JDK, compilation,
network and memory cases are matched on messages Gradle and the Android plugin are known to print and have not been seen
on a real project.

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

The program is the application module and any other module that declares OpModes; everything else, including the FTC
SDK, is a library jar. The zip (`PackageZip.create`) holds the same entries in name order with fixed timestamps, so the
same inputs give the same bytes; `PackageZip.extract` writes only manifest-listed files, caps each at its declared size
and rejects unsafe names.

### Integrity: `manifest.json`

Format `ftcsim-prepared-program`, version 2 (`PackageManifest`, in `dev.ftcsim.importer.manifest`, which has the fields).
It records the project (name, application module, namespace, a `sourceFingerprint`: SHA-256 over the original's file
hashes), the SDK version and components, the jar paths, the OpModes and the pre-flight warnings, and lists **every file
but itself with its SHA-256 and size**. There are no timestamps, so equal inputs give equal manifests. `extras` is an
object for what later parts attach (robot profile, field references); the importer writes `{}` and keeps what it finds.

JSON is read strictly (Jackson): unknown or missing properties, nulls, duplicate keys, trailing text and wrong types are
errors, and nothing has a silent default. `PreparedProgram.open(directory)` verifies structure and every hash and
rejects, naming the property or file: a different format; a newer version than the reader's ("made by a newer FTC-Sim,
so update"); unsafe or duplicate paths; a listed file that is missing or has another size or SHA-256; and any file in the
folder the manifest does not list. Version 1 (importer revision 1, no `importerRevision`, no `resources.javaResources`) is
still read, as a version 2 manifest of revision 1 without Java resources.

### Importer revision

`ProjectImporter.IMPORTER_REVISION` (2) is written into every package and read back by
`PreparedProgram.importerRevision()`. It is raised whenever a package would come out different or better, so a program
that keeps packages (the acceptance tests keep Hornet's in `%LOCALAPPDATA%\FTC-Sim\hornet-package`; `HornetFixture`) can
tell a stale one and prepare again. Revision 2 added the libraries' Java resources; a package of an older revision still
opens, with one more finding, `JAVA_RESOURCES_MISSING`, made on reading.

### Using a package

`PreparedProgram.open(directory)` verifies structure and every hash; `robotClasspath()` (program, R jar, resources
folder, libraries, Java resources) goes after the simulator's own jars (`android-shim`, `sdk-common`, `robot-runtime`,
never in the package) into `new RobotProgram(robot, program.applicationClasspath(), "description")`. **The SDK is given
`applicationClasspath()` (program, then the library jars, then the Java resources jar) as the application's code**, not
the program alone: the SDK, and libraries such as Panels, scan the whole installed application for OpModes, hooks and
annotated settings.

## Java resources

The Android Gradle plugin (AGP) hands the compiler, and the importer's `android-classes-jar` view, **class jars with every
non-class file taken out**: images, `META-INF/services` provider files, properties. On a device AGP merges those files
into the APK, so `ClassLoader.getResourceAsStream("decode-dark.png")` (Panels' field images) and `ServiceLoader` work. A
package that had only the stripped jars made Panels die at start-up with `FileNotFoundException: Resource not found:
decode-dark.png`.

The init script therefore also exports the artifact view `android-java-res` of the application's `debugRuntimeClasspath`
(AAR `classes.jar` and `libs/*.jar` as published, plain jars whole, each module's processed `java_res`, and the
application's own `src/main/resources`). `resources/java-res.jar` is one deterministic jar of the files from all of
these, merged by `JavaResourceMerger` with the rules AGP 8.7.0 applies to an application (`PackagingRules` holds the
exclude list):

- `.class` and `.so` files are not Java resources; AGP's 31 default excludes (licence and notice files, signatures,
  `META-INF/maven/**`, dot files, `*~` and so on) are left out.
- `META-INF/services/**` and `jacoco-agent.properties` are merged: same-named files joined in order.
- Any other file: the first source wins (the application's own, then the project's modules, then the libraries, each in
  class path order). AGP would stop the build on differing duplicates; the importer keeps the first and adds a
  `JAVA_RESOURCE_CONFLICT` warning naming the file and the sources.
- The project's own `packagingOptions` are not read (Hornet has only `pickFirst '**/*.so'`, which does not concern Java
  resources).

For Hornet the package's `java-res.jar` holds the same 71 files with the same SHA-256 as AGP's own
`mergeDebugJavaResource` output, among them both field images, which `HornetImportTest` loads through a class loader.

## OpModes: found in class files, and registered in code

The scan applies the rules of the SDK's own `AnnotatedOpModeClassFilter`: a public class that extends `OpMode` and has
`@TeleOp` or `@Autonomous` (not both), is not a non-static inner class, has a legal name and is not `@Disabled`;
same-named OpModes are told apart as the SDK does. The SDK host's list is the authority once a robot world runs.

OpModes that code registers at start-up (`@OpModeRegistrar` methods, as Panels uses) cannot be found in class files. The
import points at those methods with `OPMODE_REGISTRAR` warnings (the SDK's own are left out), which explains a host list
longer than `opModes.list`: on Hornet the scan finds 11 and the SDK host lists 12, the extra one being Panels'
"Enable/Disable Panels".

## Compatibility pre-flight

Static and cheap; each warning gives a class and, where it applies, a method. They are pointers, **not claims that
anything fails at run time**. The codes are in `dev.ftcsim.importer.scan.Finding.Codes`:

| Code | Meaning |
| --- | --- |
| `ANDROID_CLASS_MISSING`, `ANDROID_MEMBER_MISSING` | `android.*` or `dalvik.*` classes or members that the simulator's shim lacks (checked against the jars of `ImportRequest.withSimulatorClasspath`; `ANDROID_CHECK_SKIPPED` when none was given) |
| `CLASS_NOT_PROVIDED` | a class the code uses that is on no entry of the program's class path, in the JDK, or in the shim |
| `NATIVE_LIBRARY_LOAD`, `NATIVE_LIBRARY_PACKAGED` | `System.loadLibrary` or `System.load`; an AAR or module with Android `.so` files, which a desktop JVM cannot load |
| `THREAD_USE`, `NETWORK_USE` | code that creates threads, timers, executors or pools; sockets, an `HttpClient` or a `URL` |
| `OPMODE_REJECTED`, `OPMODE_DUPLICATE_NAME`, `OPMODE_REGISTRAR` | see OpModes above |
| `JAVA_RESOURCE_CONFLICT`, `JAVA_RESOURCES_MISSING` | see Java resources and Importer revision |
| `SDK_VERSION_MIXED`, `LINK_SKIPPED`, `TRUNCATED` | SDK artifacts of different versions; a link not followed; more findings than the manifest keeps (5000) |

## Android resources without aapt

`dev.ftcsim.importer.resources.ResourceBuilder` reads each AAR's `R.txt` and `res/values` and the project modules' own
`res` folders, and writes `r.jar` (R classes as ASM bytecode, so no compiler is needed; ids sorted so equal inputs give
equal bytes), `ftcsim-resources.tsv` and `assets/`. The table has strings, integers, booleans and colours (references
resolved; later libraries override earlier ones, the application last); other resource types get ids but no row. The
shim's `Resources` reads the table and its `AssetManager` reads `assets/`. Not generated: the styleable arrays of the
team's own `res` folders (those of libraries come from their `R.txt`). `acceptance-tests` runs the same code as a
`JavaExec` (`GenerateResources`), so the tests and the importer cannot differ.

## Verified on Hornet

`HornetImportTest` (`project-import`) and `HornetPackageTest` (`acceptance-tests`) import the pinned Hornet project (FTC
SDK 11.1.0, AGP 8.7.0, Pedro Pathing, SolversLib, FullPanels) from its unmodified source with a JDK 21. They need the
Android SDK, so they run only with `-Pftcsim.hornet=<path to the Hornet checkout>` (optionally `-Pftcsim.jdk=<JDK home>`
and `-Pftcsim.androidSdk=<folder>`). A warm import takes 18 to 28 s.

## Not done

- Licence notices of the libraries are not collected; the manifest records their coordinates only.
- Installing a JDK or an Android SDK (finding them is the application's, `app.md`).
- OpModes registered by code cannot be listed before the program runs.
- A project's own `packagingOptions` are not read, so a project that excludes, picks first or merges other files would
  get a slightly different set of Java resources.
