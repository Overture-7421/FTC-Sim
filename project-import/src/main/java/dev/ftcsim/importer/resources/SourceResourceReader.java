package dev.ftcsim.importer.resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads the resources of a module of the team's own project from its {@code res} and {@code assets} folders, which
 * is all a module has before aapt2 has run. The names come from the files and from the elements of {@code
 * res/values*}, plus every {@code @+id/} a layout or other XML file declares. The default {@code res/values}
 * folder also provides the values of the kinds the simulator answers (strings, integers, booleans and colors).
 *
 * <p>Styleables are not read: a robot project's own resources hardly ever declare any, and the simulator never
 * inflates a view.
 */
final class SourceResourceReader {
    private static final Pattern NEW_ID = Pattern.compile("@\\+id/([A-Za-z0-9_.]+)");
    /** The element of a values file by the type of resource it defines. */
    private static final Map<String, String> VALUE_TYPES = Map.ofEntries(
            Map.entry("string", "string"),
            Map.entry("integer", "integer"),
            Map.entry("bool", "bool"),
            Map.entry("color", "color"),
            Map.entry("dimen", "dimen"),
            Map.entry("fraction", "fraction"),
            Map.entry("drawable", "drawable"),
            Map.entry("string-array", "array"),
            Map.entry("integer-array", "array"),
            Map.entry("array", "array"),
            Map.entry("plurals", "plurals"),
            Map.entry("style", "style"),
            Map.entry("attr", "attr"));

    private SourceResourceReader() {}

    /**
     * @param resourceDirectories the module's {@code res} folders, the less specific (main) first
     * @param assetDirectories    the module's {@code assets} folders, in the same order
     */
    static ResourceLibrary read(String packageName, List<Path> resourceDirectories, List<Path> assetDirectories)
            throws IOException {
        Map<String, SortedSet<String>> symbols = new HashMap<>();
        List<ResourceLibrary.Value> values = new ArrayList<>();
        for (Path resources : resourceDirectories) {
            List<Path> folders;
            try (Stream<Path> listing = Files.list(resources)) {
                folders = listing.filter(Files::isDirectory).sorted().toList();
            }
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                String type = name.contains("-") ? name.substring(0, name.indexOf('-')) : name;
                if (name.startsWith(".")) {
                    continue;
                }
                if (type.equals("values")) {
                    readValues(folder, name.equals("values"), symbols, values);
                } else {
                    readFiles(folder, type, symbols);
                }
            }
        }
        return new ResourceLibrary(
                packageName, true, symbols, Map.of(), Map.of(), Map.of(), values, new DirectoryAssets(assetDirectories));
    }

    private static void readValues(
            Path folder, boolean defaultConfiguration, Map<String, SortedSet<String>> symbols, List<ResourceLibrary.Value> values)
            throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(folder)) {
            files = listing.filter(file -> file.getFileName().toString().endsWith(".xml")).sorted().toList();
        }
        for (Path file : files) {
            List<Element> elements;
            try (InputStream in = Files.newInputStream(file)) {
                elements = ValuesXml.elements(in, file.toString());
            }
            for (Element element : elements) {
                String tag = element.getTagName();
                String name = ValuesXml.symbolName(element.getAttribute("name"));
                if (tag.equals("declare-styleable")) {
                    for (Element attribute : children(element, "attr")) {
                        add(symbols, "attr", ValuesXml.symbolName(attribute.getAttribute("name")));
                    }
                } else if (tag.equals("item") && !element.getAttribute("type").isEmpty()) {
                    add(symbols, element.getAttribute("type"), name);
                } else if (VALUE_TYPES.containsKey(tag) && !name.isEmpty()) {
                    add(symbols, VALUE_TYPES.get(tag), name);
                    if (defaultConfiguration && ValuesXml.READABLE_TYPES.contains(tag)) {
                        values.add(new ResourceLibrary.Value(tag, name, element.getTextContent()));
                    }
                }
            }
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> found = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(tag) && !element.getAttribute("name").isEmpty()) {
                found.add(element);
            }
        }
        return found;
    }

    private static void readFiles(Path folder, String type, Map<String, SortedSet<String>> symbols) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(folder)) {
            files = listing.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.startsWith(".")) {
                continue;
            }
            int dot = fileName.indexOf('.');
            add(symbols, type, dot < 0 ? fileName : fileName.substring(0, dot));
            if (fileName.endsWith(".xml")) {
                Matcher ids = NEW_ID.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (ids.find()) {
                    add(symbols, "id", ValuesXml.symbolName(ids.group(1)));
                }
            }
        }
    }

    private static void add(Map<String, SortedSet<String>> symbols, String type, String name) {
        if (!name.isEmpty()) {
            symbols.computeIfAbsent(type, key -> new TreeSet<>()).add(name);
        }
    }

    /** The files of the module's asset folders; a later folder replaces files of the same name. */
    private record DirectoryAssets(List<Path> directories) implements ResourceLibrary.Assets {
        @Override
        public void copyInto(Path target) throws IOException {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> walk = Files.walk(directory)) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path file : files) {
                    Path destination = target.resolve(directory.relativize(file).toString()).normalize();
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
