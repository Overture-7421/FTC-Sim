package dev.ftcsim.importer.resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Command line entry for builds that need the Android resources of some libraries without importing a whole
 * project, such as the acceptance tests, which run the FTC SDK's own AARs. The libraries are listed in a file,
 * one path per line, because a list of AARs can be longer than a Windows command line allows. They are taken in
 * the order of their file names, so the same set of AARs always gives the same ids.
 *
 * <p>Usage: {@code GenerateResources <output directory> <file listing the AARs>}. The output directory gets
 * {@value ResourceBuilder#R_JAR}, {@value ResourceBuilder#TABLE} and the folder {@value ResourceBuilder#ASSETS}.
 */
public final class GenerateResources {
    private GenerateResources() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: GenerateResources <output directory> <file listing the AARs, one path per line>");
            System.exit(2);
        }
        List<Path> aars = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(Path::of)
                .sorted(Comparator.comparing(aar -> aar.getFileName().toString()))
                .toList();
        ResourceBuilder builder = new ResourceBuilder();
        for (Path aar : aars) {
            builder.addAar(aar);
        }
        ResourceBuilder.Summary summary = builder.writeTo(Path.of(args[0]));
        System.out.println("Android resources of " + summary.libraries() + " libraries: " + summary.resources()
                + " resources in " + summary.packages() + " packages, " + summary.tableRows() + " values in the table");
    }
}
