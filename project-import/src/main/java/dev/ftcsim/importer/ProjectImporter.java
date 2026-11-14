package dev.ftcsim.importer;

import dev.ftcsim.importer.ImportException.Failure;
import dev.ftcsim.importer.manifest.PackageManifest;

/**
 * The entry point of an import; see the package documentation for what it does and what it executes.
 */
public final class ProjectImporter {
    /**
     * The revision of this importer, which every package it makes records (see {@code PreparedProgram#importerRevision}).
     * A program that caches packages compares it with the cached package's revision and prepares the program again when
     * the cached one is older. It is raised whenever a package would come out different or better: revision 2 put the
     * Java resources of the libraries into the package, revision 1 left them out.
     */
    public static final int IMPORTER_REVISION = PackageManifest.IMPORTER_REVISION;

    private ProjectImporter() {}

    /**
     * Imports the project and creates the prepared program, and the zip when the request asks for one.
     *
     * @param progress told of each stage and of the project's build output; asked often whether to stop
     * @throws ImportException when no package could be made; nothing of the package is left behind, and the original
     *                         project has not been touched
     */
    public static ImportResult importProject(ImportRequest request, ImportProgress progress) {
        if (request.trust() != BuildTrust.TRUST_BUILD_SCRIPTS) {
            throw new ImportException(
                    Failure.NOT_TRUSTED,
                    "Importing a project runs its build scripts on this computer, and they are not trusted, so nothing was "
                            + "copied or started. Import only projects whose source you are willing to run, and say so when "
                            + "you ask for the import.");
        }
        return new ImportRun(request, progress).run();
    }
}
