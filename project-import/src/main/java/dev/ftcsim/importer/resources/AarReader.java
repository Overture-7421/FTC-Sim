package dev.ftcsim.importer.resources;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;

/**
 * Reads an Android library (AAR) the way aapt2 would have had to: its package from the manifest, its resource
 * names and styleables from {@code R.txt}, its strings, integers, booleans and colors from {@code res/values},
 * and its raw assets.
 */
final class AarReader {
    private static final Pattern PACKAGE = Pattern.compile("package=\"([^\"]+)\"");
    private static final Pattern VALUES_FILE = Pattern.compile("res/values/[^/]+\\.xml");
    private static final String ASSETS = "assets/";

    private AarReader() {}

    static ResourceLibrary read(Path aar) throws IOException {
        try (ZipFile zip = new ZipFile(aar.toFile())) {
            Matcher declared = PACKAGE.matcher(text(zip, "AndroidManifest.xml"));
            if (!declared.find()) {
                throw new ResourceException(aar.getFileName() + " has no package in its manifest");
            }
            Map<String, SortedSet<String>> symbols = new HashMap<>();
            Map<String, List<String>> arrays = new LinkedHashMap<>();
            Map<String, String> indexes = new LinkedHashMap<>();
            Map<String, String> attributeNames = new HashMap<>();
            text(zip, "R.txt").lines().map(String::trim).filter(line -> !line.isEmpty()).forEach(line -> {
                String[] parts = line.split(" ");
                if (parts.length < 3) {
                    return;
                }
                if (parts[0].equals("int[]")) {
                    arrays.put(parts[2], elementsOf(line));
                } else if (parts[1].equals("styleable") && parts.length > 3) {
                    indexes.put(parts[2], parts[3]);
                } else {
                    symbols.computeIfAbsent(parts[1], type -> new TreeSet<>()).add(parts[2]);
                    if (parts[1].equals("attr") && parts.length > 3) {
                        attributeNames.put(parts[3], parts[2]);
                    }
                }
            });
            return new ResourceLibrary(
                    declared.group(1), false, symbols, arrays, indexes, attributeNames, values(zip, aar), new AarAssets(aar));
        }
    }

    /** The attribute ids between the braces of an {@code int[] styleable Name { 0x7f04..., ... }} line. */
    private static List<String> elementsOf(String line) {
        int open = line.indexOf('{');
        int close = line.indexOf('}');
        if (open < 0 || close < open) {
            return List.of();
        }
        return Arrays.stream(line.substring(open + 1, close).split(","))
                .map(String::trim)
                .filter(element -> !element.isEmpty())
                .toList();
    }

    private static List<ResourceLibrary.Value> values(ZipFile zip, Path aar) throws IOException {
        List<ResourceLibrary.Value> values = new ArrayList<>();
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (VALUES_FILE.matcher(entry.getName()).matches()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    for (Element element : ValuesXml.elements(in, aar.getFileName() + "!" + entry.getName())) {
                        if (ValuesXml.READABLE_TYPES.contains(element.getTagName())) {
                            values.add(new ResourceLibrary.Value(
                                    element.getTagName(),
                                    ValuesXml.symbolName(element.getAttribute("name")),
                                    element.getTextContent()));
                        }
                    }
                }
            }
        }
        return values;
    }

    private static String text(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return "";
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The files below {@code assets/} of the archive. */
    private record AarAssets(Path aar) implements ResourceLibrary.Assets {
        @Override
        public void copyInto(Path target) throws IOException {
            try (ZipFile zip = new ZipFile(aar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(ASSETS)) {
                        continue;
                    }
                    Path file = target.resolve(entry.getName().substring(ASSETS.length())).normalize();
                    if (!file.startsWith(target)) {
                        throw new ResourceException(aar.getFileName() + " holds an asset outside its assets folder: " + entry.getName());
                    }
                    Files.createDirectories(file.getParent());
                    try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(file)) {
                        in.transferTo(out);
                    }
                }
            }
        }
    }
}
