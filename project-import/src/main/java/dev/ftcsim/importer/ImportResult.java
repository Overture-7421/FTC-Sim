package dev.ftcsim.importer;

import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.snapshot.SourceManifest;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A finished import.
 *
 * @param program       the prepared program, already opened and checked
 * @param packageZip    the zip of it, when one was asked for
 * @param gradleLog     the complete output of the project's build
 * @param originalFiles the hashes of the original project's source and build files, which were the same before and
 *                      after the import
 */
public record ImportResult(PreparedProgram program, Optional<Path> packageZip, Path gradleLog, SourceManifest originalFiles) {}
