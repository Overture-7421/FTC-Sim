package dev.ftcsim.physics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal reader for the import tables of a 64-bit PE image. It reports which DLLs, and which
 * functions of each, a native library needs the loader to find; delay-loaded imports are included.
 */
final class PeImports {

    private static final int IMPORT_DIRECTORY = 1;
    private static final int DELAY_IMPORT_DIRECTORY = 13;
    private static final int PE32_PLUS_MAGIC = 0x20b;

    private final ByteBuffer image;
    private final List<Section> sections = new ArrayList<>();

    private record Section(long virtualAddress, long virtualSize, long rawPointer) {}

    private PeImports(byte[] bytes) {
        image = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Imported function names per DLL name (as written in the image), sorted case-insensitively. */
    static Map<String, List<String>> read(byte[] pe) {
        return new PeImports(pe).readAll();
    }

    private Map<String, List<String>> readAll() {
        int peOffset = image.getInt(0x3c);
        if (image.getInt(peOffset) != 0x00004550) {
            throw new IllegalArgumentException("not a PE image");
        }
        int coff = peOffset + 4;
        int sectionCount = Short.toUnsignedInt(image.getShort(coff + 2));
        int optionalSize = Short.toUnsignedInt(image.getShort(coff + 16));
        int optional = coff + 20;
        if (Short.toUnsignedInt(image.getShort(optional)) != PE32_PLUS_MAGIC) {
            throw new IllegalArgumentException("only 64-bit images are supported");
        }
        int directories = optional + 112;
        int sectionTable = optional + optionalSize;
        for (int i = 0; i < sectionCount; i++) {
            int at = sectionTable + 40 * i;
            sections.add(new Section(
                    Integer.toUnsignedLong(image.getInt(at + 12)),
                    Integer.toUnsignedLong(image.getInt(at + 8)),
                    Integer.toUnsignedLong(image.getInt(at + 20))));
        }
        Map<String, List<String>> imports = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        readImports(imports, directoryRva(directories, IMPORT_DIRECTORY));
        readDelayImports(imports, directoryRva(directories, DELAY_IMPORT_DIRECTORY));
        return imports;
    }

    private long directoryRva(int directories, int index) {
        return Integer.toUnsignedLong(image.getInt(directories + 8 * index));
    }

    private void readImports(Map<String, List<String>> imports, long tableRva) {
        if (tableRva == 0) {
            return;
        }
        for (int at = offset(tableRva); image.getInt(at + 12) != 0; at += 20) {
            long thunks = Integer.toUnsignedLong(image.getInt(at));
            if (thunks == 0) {
                thunks = Integer.toUnsignedLong(image.getInt(at + 16));
            }
            addFunctions(imports, Integer.toUnsignedLong(image.getInt(at + 12)), thunks);
        }
    }

    private void readDelayImports(Map<String, List<String>> imports, long tableRva) {
        if (tableRva == 0) {
            return;
        }
        for (int at = offset(tableRva); image.getInt(at + 4) != 0; at += 32) {
            addFunctions(imports, Integer.toUnsignedLong(image.getInt(at + 4)), Integer.toUnsignedLong(image.getInt(at + 16)));
        }
    }

    private void addFunctions(Map<String, List<String>> imports, long nameRva, long thunksRva) {
        List<String> functions = imports.computeIfAbsent(string(offset(nameRva)), name -> new ArrayList<>());
        for (int at = offset(thunksRva); image.getLong(at) != 0; at += 8) {
            long entry = image.getLong(at);
            functions.add(entry < 0
                    ? "#" + (entry & 0xffff)
                    : string(offset(entry & 0x7fffffffL) + 2));
        }
    }

    private int offset(long rva) {
        for (Section section : sections) {
            if (rva >= section.virtualAddress() && rva < section.virtualAddress() + Math.max(section.virtualSize(), 1)) {
                return Math.toIntExact(rva - section.virtualAddress() + section.rawPointer());
            }
        }
        throw new IllegalArgumentException("RVA outside every section: 0x" + Long.toHexString(rva));
    }

    private String string(int offset) {
        int end = offset;
        while (image.get(end) != 0) {
            end++;
        }
        byte[] bytes = new byte[end - offset];
        image.get(offset, bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /** Prints the imports of each DLL named on the command line. */
    public static void main(String[] args) throws IOException {
        for (String file : args) {
            System.out.println(file);
            read(Files.readAllBytes(Path.of(file)))
                    .forEach((dll, functions) -> System.out.println("  " + dll + " (" + functions.size() + " functions)"));
        }
    }
}
