/**
 * Turns an unmodified FTC team project into a prepared robot program for the simulator, and the program into a
 * practice package that needs no Android Studio, Android SDK, Gradle, Git or compiler to open.
 *
 * <p>Use {@link ProjectImporter#importProject}. The steps, which a user interface can follow through
 * {@link ImportProgress}:
 * <ol>
 *   <li>{@link ImportStage#SNAPSHOT}: the project is copied into the workspace, skipping build outputs, {@code .git},
 *       {@code .gradle} and IDE folders, while the SHA-256 of every source and build file of the original is
 *       recorded. All later work happens in the copy. At the end the original is hashed again; if anything differs
 *       the import fails and produces nothing.</li>
 *   <li>{@link ImportStage#GRADLE_EXPORT}: the copy's own Gradle wrapper builds the application module with one
 *       init script added on the command line (see {@code ftcsim-export.init.gradle}). Gradle and the Android
 *       Gradle plugin therefore resolve the project's real repositories, variants and dependency rules, and compile
 *       the team's code as the project does. The script reports the compiled classes, the resolved runtime class
 *       path and the resources of the project's modules.</li>
 *   <li>{@link ImportStage#RESOURCES}: the Android resources are generated without aapt: an id for every
 *       resource, the {@code R} classes as bytecode, the table of strings, integers, booleans and colors, and the
 *       assets (see {@code dev.ftcsim.importer.resources}).</li>
 *   <li>{@link ImportStage#PACKAGE} and {@link ImportStage#ANALYSIS}: everything is laid out as a package with a
 *       versioned manifest (see {@code dev.ftcsim.importer.pack}); the Java resources of the libraries (images, service
 *       provider files), which the class jars of the build do not hold, are merged into one jar as the Android Gradle
 *       plugin merges them into an APK (see {@code dev.ftcsim.importer.javares}); the annotated OpModes are found by
 *       reading the bytecode (OpModes that code registers when the program starts, as a library such as Panels does,
 *       cannot be listed and are pointed out as warnings), and a static pre-flight lists code that may not work in the
 *       simulator.</li>
 *   <li>{@link ImportStage#VERIFY}: the package is opened and checked as a driver's machine will check it, the
 *       original project is hashed again, and the optional zip is written.</li>
 * </ol>
 *
 * <h2>Trust</h2>
 * Importing from source <strong>executes the project's build scripts</strong>, and the plugins and wrapper they name,
 * on this machine with the current user's rights. The importer runs them as a separate Gradle process, which is
 * <strong>not a sandbox</strong>: it limits nothing they can read, write or send. For that reason every request
 * carries a {@link BuildTrust}, there is no default, and the import refuses to start anything unless the caller
 * passes {@link BuildTrust#TRUST_BUILD_SCRIPTS} after telling the user. Opening an already prepared package runs no
 * build script and needs no such decision.
 *
 * <h2>What the importer does not do</h2>
 * It does not find or install a JDK or the Android SDK (give them in the request), edit any file of the project,
 * or run on the project's own folder. Offline use needs a Gradle cache that already holds the project's
 * dependencies, which one online import provides.
 */
package dev.ftcsim.importer;
