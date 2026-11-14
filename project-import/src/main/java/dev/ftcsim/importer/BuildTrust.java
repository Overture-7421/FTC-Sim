package dev.ftcsim.importer;

/**
 * Whether the caller accepts that importing a project <em>runs that project's build scripts</em>.
 *
 * <p>A team project's {@code build.gradle} files, its {@code settings.gradle}, its Gradle wrapper and the plugins
 * and dependencies they name are programs. Importing the project from source executes them on this machine with
 * the rights of the current user: they can read and write any file that user can, and use the network. The
 * importer starts them as a separate Gradle process, but <strong>a child process is not a sandbox</strong>; it
 * limits nothing. Only import a project whose source you would be willing to run. A prepared practice package
 * holds no build scripts, so opening one does not need this decision.
 *
 * <p>There is deliberately no default: a caller has to choose, and {@code ProjectImporter} refuses to start
 * anything unless the answer is {@link #TRUST_BUILD_SCRIPTS}.
 */
public enum BuildTrust {
    /** The user was told that the project's build scripts will run, and agreed. */
    TRUST_BUILD_SCRIPTS,
    /** The project is not trusted; the import stops before it copies or runs anything. */
    DO_NOT_TRUST
}
