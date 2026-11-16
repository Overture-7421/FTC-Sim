# Importing a team project

What the importer (`project-import`, package `dev.ftcsim.importer`) turns a team's Gradle project into, and the trust, integrity and compatibility rules around it; read it when you change the importer or the prepared-program format.

An **import** turns an unmodified FTC project (Gradle, Android Studio layout) into a **prepared program**: a folder, optionally zipped, that the simulator runs without Android Studio, the Android SDK, Gradle, Git or a compiler. Only the import needs those. The window calls an import a *build* and keeps the programs itself ([app.md](app.md)); the JDK home and Android SDK folder are parameters, and finding them is `app`'s job.

## Stages

`ProjectImporter.importProject(ImportRequest, ImportProgress)` runs these; `ImportProgress` gives `stage`, `message` (each line of Gradle output) and `isCancelled` (polled; cancelling stops the build and removes the unfinished package).

1. **SNAPSHOT.** Copies the project into the caller's workspace without build outputs, `.git`, `.gradle`, IDE folders, `local.properties`, `*.iml` and `ftcsim.json` (not robot source, so editing it never causes a build). Records the SHA-256 of every original file. **The import fails and produces nothing if the original differs when hashed again at the end.** All Gradle runs happen in the copy.
2. **GRADLE_EXPORT.** Runs the copy's own Gradle wrapper with one init script added on the command line (`ftcsim-export.init.gradle`, a resource), so the project's repositories, variants and dependency rules apply as in Android Studio. The script reports compiled classes, the resolved runtime class path, AARs, Java resources, the project's resources and assets, and the `org.firstinspires.ftc` artifacts, from which the **FTC SDK version** is read. `ANDROID_HOME` and `JAVA_HOME` go in the child's environment; nothing is written to the project.
3. **RESOURCES.** Generates Android resources without aapt (below).
4. **PACKAGE, ANALYSIS.** Lays out files, merges Java resources, finds OpModes and runs the pre-flight, both by reading bytecode with ASM (no class is loaded), and writes the manifest.
5. **VERIFY.** Opens the package exactly as a driver's machine does, hashes the original again, and writes the zip if asked.

## Trust: a child process is not a sandbox

Importing from source **executes the project's build scripts**, and the plugins and wrapper they name, with the current user's rights, in a separate Gradle process that is **not a sandbox**: it limits nothing the scripts can read, write or send.

- Every `ImportRequest` carries a `BuildTrust`; there is no default, and nothing starts unless the caller passes `TRUST_BUILD_SCRIPTS` after telling the user. The application takes the person's choice of the project folder as that decision.
- Opening a prepared package runs no build script and needs no such decision.

## Offline and failures

- An import with network access fills the Gradle cache; a later one with `withOffline(true)` and the same `withGradleUserHome` runs without a network (a cold cache is `OFFLINE_CACHE_COLD`). A made package needs no network and no Gradle.
- `ImportException.failure()` names the cause (the enum lists them); the message says what to do, `diagnostics()` has the explaining Gradle lines and `logFile()` the full output.
- The classifier was written against real Hornet/Gradle 8.9 output for the Android SDK, licence, offline and dependency cases. The wrong-JDK, compilation, network and memory cases match messages Gradle and the Android plugin are known to print but **have not been seen on a real project**.

## The prepared program

```
manifest.json                     the manifest; the one file it does not list
program/<module>.jar              the team's compiled code
libs/<nnn>-<library>.jar          the libraries, numbered in class path order
resources/r.jar                   generated Android R classes (bytecode)
resources/ftcsim-resources.tsv    strings, integers, booleans and colours by resource id
resources/assets/...              the libraries' and the project's raw assets
resources/java-res.jar            the libraries' and project's Java resources, merged (absent when none)
```

The program is the application module plus any module that declares OpModes; everything else, the FTC SDK included, is a library jar. The zip (`PackageZip.create`) holds the same entries in name order with fixed timestamps, so equal inputs give equal bytes. `PackageZip.extract` writes only manifest-listed files, caps each at its declared size and rejects unsafe names.

### Integrity: `manifest.json`

Format `ftcsim-prepared-program`, version 2 (`PackageManifest` has the fields). It records the project (name, application module, namespace, `sourceFingerprint`: SHA-256 over the original's file hashes), the SDK version and components, jar paths, OpModes and pre-flight warnings, and lists **every file but itself with its SHA-256 and size**. No timestamps. `extras` is for what later parts attach (the importer writes `{}` and keeps what it finds).

JSON is read strictly (Jackson): unknown or missing properties, nulls, duplicate keys, trailing text and wrong types are errors; nothing has a silent default. `PreparedProgram.open(directory)` verifies structure and every hash, and rejects, naming the property or file:

- a different format, or a newer version than the reader's ("made by a newer FTC-Sim, so update");
- unsafe or duplicate paths;
- a listed file that is missing or has another size or SHA-256;
- any file in the folder that the manifest does not list.

Version 1 (importer revision 1, no `importerRevision`, no `resources.javaResources`) is still read, as a version 2 manifest of revision 1 without Java resources.

### Importer revision

`ProjectImporter.IMPORTER_REVISION` (2) is written into every package and read back by `PreparedProgram.importerRevision()`. **Raise it whenever a package would come out different or better**, so a program that keeps packages (the acceptance tests keep Hornet's in `%LOCALAPPDATA%\FTC-Sim\hornet-package`: `HornetFixture`) can tell a stale one and prepare again. Revision 2 added the libraries' Java resources; an older package still opens, with one more finding, `JAVA_RESOURCES_MISSING`, made on reading.

### Using a package

`PreparedProgram.open(directory)` verifies; `robotClasspath()` (program, R jar, resources folder, libraries, Java resources) goes after the simulator's own jars (`android-shim`, `sdk-common`, `robot-runtime`, never in the package) into `new RobotProgram(robot, program.applicationClasspath(), "description")`.

**The SDK gets `applicationClasspath()` (program, library jars, Java resources jar) as the application's code, not the program alone**: the SDK, and libraries such as Panels, scan the whole installed application for OpModes, hooks and annotated settings.

## Java resources

The Android Gradle plugin (AGP) hands the compiler, and the importer's `android-classes-jar` view, class jars with every non-class file removed (images, `META-INF/services`, properties). On a device AGP merges those into the APK, so `ClassLoader.getResourceAsStream("decode-dark.png")` (Panels' field images) and `ServiceLoader` work. A package with only the stripped jars made Panels die at start with `FileNotFoundException: Resource not found: decode-dark.png`.

So the init script also exports the artifact view `android-java-res` of the application's `debugRuntimeClasspath` (AAR `classes.jar` and `libs/*.jar` as published, plain jars whole, each module's processed `java_res`, the application's `src/main/resources`). `resources/java-res.jar` is one deterministic jar of all of them, merged by `JavaResourceMerger` with the rules AGP 8.7.0 applies to an application (`PackagingRules` has the exclude list):

- `.class` and `.so` files are not Java resources; AGP's 31 default excludes (licence and notice files, signatures, `META-INF/maven/**`, dot files, `*~`, ...) are left out.
- `META-INF/services/**` and `jacoco-agent.properties` are merged: same-named files joined in order.
- Any other file: the first source wins (application, then project modules, then libraries, each in class path order). AGP would stop on differing duplicates; the importer keeps the first and warns `JAVA_RESOURCE_CONFLICT`, naming file and sources.
- The project's own `packagingOptions` are **not read** (Hornet has only `pickFirst '**/*.so'`, which does not concern Java resources).

For Hornet the package's `java-res.jar` holds the same 71 files with the same SHA-256 as AGP's own `mergeDebugJavaResource` output, including both field images, which `HornetImportTest` loads through a class loader.

## OpModes

The scan applies the SDK's own `AnnotatedOpModeClassFilter` rules: a public class extending `OpMode` with `@TeleOp` or `@Autonomous` (not both), not a non-static inner class, with a legal name, not `@Disabled`; same-named OpModes are told apart as the SDK does. Once a robot world runs, the SDK host's list is the authority.

OpModes registered in code at start-up (`@OpModeRegistrar` methods, as Panels uses) cannot be found in class files. The importer points at those methods with `OPMODE_REGISTRAR` warnings (the SDK's own are left out). On Hornet the scan finds 11 and the SDK host lists 12, the extra being Panels' "Enable/Disable Panels".

## Compatibility pre-flight

Static and cheap. Each warning gives a class and, where it applies, a method. They are pointers, **not claims that anything fails at run time**. Codes: `dev.ftcsim.importer.scan.Finding.Codes`.

| Code | Meaning |
|---|---|
| `ANDROID_CLASS_MISSING`, `ANDROID_MEMBER_MISSING`, `ANDROID_CHECK_SKIPPED` | `android.*` or `dalvik.*` classes or members the simulator's shim lacks (checked against `ImportRequest.withSimulatorClasspath`; skipped when none is given) |
| `CLASS_NOT_PROVIDED` | A used class that is on no program class path entry, in the JDK or in the shim |
| `NATIVE_LIBRARY_LOAD`, `NATIVE_LIBRARY_PACKAGED` | `System.loadLibrary`/`load`; an AAR or module with Android `.so` files, which a desktop JVM cannot load |
| `THREAD_USE`, `NETWORK_USE` | Code that creates threads, timers, executors or pools; sockets, `HttpClient` or `URL` |
| `OPMODE_REJECTED`, `OPMODE_DUPLICATE_NAME`, `OPMODE_REGISTRAR` | See OpModes |
| `JAVA_RESOURCE_CONFLICT`, `JAVA_RESOURCES_MISSING` | See Java resources and Importer revision |
| `SDK_VERSION_MIXED`, `LINK_SKIPPED`, `TRUNCATED` | SDK artifacts of different versions; a link not followed; more findings than the manifest keeps (5000) |

## Android resources without aapt

`ResourceBuilder` (`dev.ftcsim.importer.resources`) reads each AAR's `R.txt` and `res/values` and the project modules' `res` folders, and writes `r.jar` (R classes as ASM bytecode, so no compiler; ids sorted for equal bytes), `ftcsim-resources.tsv` and `assets/`.

- The table has strings, integers, booleans and colours (references resolved; later libraries override earlier, the application last). Other resource types get ids but no row.
- The shim's `Resources` reads the table and its `AssetManager` reads `assets/`.
- **Not generated:** the styleable arrays of the team's own `res` folders (libraries' come from their `R.txt`).
- `acceptance-tests` runs the same code as a `JavaExec` (`GenerateResources`), so tests and importer cannot differ.

## Verified on Hornet

`HornetImportTest` (`project-import`) and `HornetPackageTest` (`acceptance-tests`) import the pinned Hornet project (FTC SDK 11.1.0, AGP 8.7.0, Pedro Pathing, SolversLib, FullPanels) from unmodified source with a JDK 21. They need the Android SDK, so they run only with `-Pftcsim.hornet=<Hornet checkout>` (optionally `-Pftcsim.jdk=<JDK home>`, `-Pftcsim.androidSdk=<folder>`). A warm import takes 18 to 28 s.

## Not done

- Licence notices of the libraries are not collected; the manifest records coordinates only.
- Installing a JDK or an Android SDK (finding them is `app`'s, [app.md](app.md)).
- OpModes registered by code cannot be listed before the program runs.
- A project's own `packagingOptions` are not read, so a project that excludes, picks first or merges other files gets a slightly different set of Java resources.
