package dev.ftcsim.importer.resources;

import dev.ftcsim.importer.io.Trees;
import dev.ftcsim.importer.io.ZipBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Stands in for what aapt2 does when it links an Android app. The AARs of the FTC SDK and of the team's libraries
 * carry their resources as text ({@code R.txt}, {@code res/values}), but their classes refer to a generated
 * {@code R} class per library package and ask the Android runtime to look values up by id. This gives every
 * resource of every library one id, writes an {@code R} class per package with those ids as bytecode, writes the
 * strings, integers, booleans and colors to a table that the Android shim's {@code Resources} reads from the
 * class path, and lays the libraries' raw assets out where the shim's {@code AssetManager} finds them.
 *
 * <p>Libraries are added from the one with the least say to the one with the most: when two define the same
 * value or asset, the later one wins, as in Android's resource merging where the app overrides its libraries. The
 * ids do not depend on the order. The classes of the packages of the team's own modules hold every resource of
 * the app, as the R class of an app does when R classes are transitive, which also covers projects that turn
 * that off.
 *
 * <p>The output directory then holds {@value #R_JAR}, {@value #TABLE} and the folder {@value #ASSETS}; the
 * directory and the jar both belong on the robot class path.
 */
public final class ResourceBuilder {
    public static final String R_JAR = "r.jar";
    public static final String TABLE = "ftcsim-resources.tsv";
    public static final String ASSETS = "assets";

    /** What a run produced. */
    public record Summary(int libraries, int packages, int resources, int tableRows) {}

    private record Key(String type, String name) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byType = type.compareTo(other.type);
            return byType != 0 ? byType : name.compareTo(other.name);
        }
    }

    private final List<ResourceLibrary> libraries = new ArrayList<>();

    /** Adds an Android library (an {@code .aar}). */
    public ResourceBuilder addAar(Path aar) throws IOException {
        libraries.add(AarReader.read(aar));
        return this;
    }

    /**
     * Adds a module of the team's project.
     *
     * @param packageName         the module's namespace, which names its R class
     * @param resourceDirectories its {@code res} folders, the less specific first
     * @param assetDirectories    its {@code assets} folders, the less specific first
     */
    public ResourceBuilder addModule(String packageName, List<Path> resourceDirectories, List<Path> assetDirectories)
            throws IOException {
        libraries.add(SourceResourceReader.read(packageName, resourceDirectories, assetDirectories));
        return this;
    }

    /**
     * Writes {@value #R_JAR}, {@value #TABLE} and {@value #ASSETS} into {@code directory}, replacing earlier ones. The
     * jar is not rewritten when it would come out the same, so a generation that changes nothing also works while a
     * robot world has the old jar open.
     */
    public Summary writeTo(Path directory) throws IOException {
        Files.createDirectories(directory);
        for (String stale : List.of(TABLE, ASSETS)) {
            Trees.delete(directory.resolve(stale));
        }
        SortedMap<String, SortedSet<String>> names = union(libraries);
        Map<String, Integer> ids = assignIds(names);

        ZipBuilder jar = new ZipBuilder();
        Map<String, List<ResourceLibrary>> packages = new TreeMap<>();
        for (ResourceLibrary library : libraries) {
            packages.computeIfAbsent(library.packageName, name -> new ArrayList<>()).add(library);
        }
        for (Map.Entry<String, List<ResourceLibrary>> entry : packages.entrySet()) {
            boolean wholeApp = entry.getValue().stream().anyMatch(library -> library.ofTheProject);
            writePackage(jar, entry.getKey(), wholeApp ? libraries : entry.getValue(), ids);
        }
        jar.writeTo(directory.resolve(R_JAR));

        int rows = writeTable(directory.resolve(TABLE), ids);
        Path assets = directory.resolve(ASSETS);
        for (ResourceLibrary library : libraries) {
            library.assets.copyInto(assets);
        }
        int resources = names.values().stream().mapToInt(SortedSet::size).sum();
        return new Summary(libraries.size(), packages.size(), resources, rows);
    }

    private static SortedMap<String, SortedSet<String>> union(Collection<ResourceLibrary> contributors) {
        SortedMap<String, SortedSet<String>> names = new TreeMap<>();
        for (ResourceLibrary library : contributors) {
            library.symbols.forEach((type, symbols) -> names.computeIfAbsent(type, key -> new TreeSet<>()).addAll(symbols));
        }
        return names;
    }

    /** The id of a resource is 0x7f, the position of its type among all types, and the position of its name in the type. */
    private static Map<String, Integer> assignIds(SortedMap<String, SortedSet<String>> names) {
        Map<String, Integer> ids = new HashMap<>();
        int typeIndex = 0;
        for (Map.Entry<String, SortedSet<String>> type : names.entrySet()) {
            typeIndex++;
            if (type.getValue().size() > 0xFFFF) {
                throw new ResourceException("There are " + type.getValue().size() + " resources of type " + type.getKey()
                        + ", more than the 65535 an id can number");
            }
            int entryIndex = 0;
            for (String name : type.getValue()) {
                ids.put(type.getKey() + "/" + name, 0x7f000000 | (typeIndex << 16) | entryIndex++);
            }
        }
        return ids;
    }

    private static void writePackage(ZipBuilder jar, String packageName, List<ResourceLibrary> contributors, Map<String, Integer> ids) {
        SortedMap<String, int[]> arrays = new TreeMap<>();
        SortedMap<String, Integer> indexes = new TreeMap<>();
        for (ResourceLibrary library : contributors) {
            library.styleableArrays.forEach((name, elements) -> arrays.put(name, attributeIds(library, elements, ids)));
            library.styleableIndexes.forEach((name, index) -> {
                try {
                    indexes.put(name, Integer.parseInt(index));
                } catch (NumberFormatException notAnIndex) {
                    // a line of R.txt that is not a position cannot be a field either
                }
            });
        }
        RClassWriter.write(packageName, union(contributors), ids, arrays, indexes).forEach(jar::add);
    }

    /** The attributes of a styleable under the ids this run assigned; framework attributes keep the id Android gave them. */
    private static int[] attributeIds(ResourceLibrary library, List<String> elements, Map<String, Integer> ids) {
        int[] attributes = new int[elements.size()];
        for (int i = 0; i < attributes.length; i++) {
            String attribute = library.attributeNames.get(elements.get(i));
            Integer assigned = attribute == null ? null : ids.get("attr/" + attribute);
            attributes[i] = assigned != null ? assigned : asWritten(elements.get(i));
        }
        return attributes;
    }

    private static int asWritten(String id) {
        try {
            return (int) Long.decode(id).longValue();
        } catch (NumberFormatException notAnId) {
            return 0;
        }
    }

    /** One line per resource of a kind the simulator answers: type, id, name and value, tab separated. */
    private int writeTable(Path file, Map<String, Integer> ids) throws IOException {
        Map<String, String> strings = new HashMap<>();
        Map<String, String> dimensions = new HashMap<>();
        SortedMap<Key, ResourceLibrary.Value> effective = new TreeMap<>();
        for (ResourceLibrary library : libraries) {
            for (ResourceLibrary.Value value : library.values) {
                effective.put(new Key(value.type(), value.name()), value);
                if (value.type().equals("string")) {
                    strings.put(value.name(), value.text());
                } else if (value.type().equals("dimen")) {
                    dimensions.put(value.name(), value.text());
                }
            }
        }
        StringBuilder table = new StringBuilder();
        int rows = 0;
        for (ResourceLibrary.Value value : effective.values()) {
            Integer id = ids.get(value.type() + "/" + value.name());
            if (id == null) {
                continue;
            }
            String text = switch (value.type()) {
                case "string" -> ResourceValues.resolveReference(ResourceValues.resolveString(value.text()), strings);
                case "dimen" -> ResourceValues.resolveDimenReference(value.text().trim(), dimensions);
                default -> value.text().trim();
            };
            table.append(value.type()).append("\t0x").append(Integer.toHexString(id)).append('\t').append(value.name())
                    .append('\t').append(ResourceValues.escape(text)).append('\n');
            rows++;
        }
        Files.write(file, table.toString().getBytes(StandardCharsets.UTF_8));
        return rows;
    }
}
