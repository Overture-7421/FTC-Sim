package dev.ftcsim.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the zip archives the fake download service serves: a small fixture JDK, and deliberately hostile archives whose
 * entry names and attributes are written verbatim (java.util.zip does not judge names, which is what the tests need).
 */
final class FixtureZip {

    private static final int CENTRAL_HEADER = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY = 0x06054b50;
    private static final int UNIX_HOST = 3 << 8;

    private record Entry(String name, byte[] content) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> externalAttributes = new HashMap<>();
    private final Map<String, Boolean> unixHosted = new HashMap<>();

    /** Every file below {@code directory}, stored under {@code topLevel/} like the folder inside a Temurin zip. */
    static FixtureZip ofDirectory(Path directory, String topLevel) throws IOException {
        FixtureZip zip = new FixtureZip();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = directory.relativize(file).toString().replace('\\', '/');
                zip.file(topLevel + "/" + relative, Files.readAllBytes(file));
            }
        }
        return zip;
    }

    FixtureZip file(String name, byte[] content) {
        entries.add(new Entry(name, content));
        return this;
    }

    FixtureZip file(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Incompressible content, so the archive has a realistic size and a download can be cut in the middle. */
    FixtureZip padding(String name, int bytes) {
        byte[] content = new byte[bytes];
        new Random(7).nextBytes(content);
        return file(name, content);
    }

    /** Marks an entry as a Unix symbolic link, the way Info-ZIP and 7-Zip store one. */
    FixtureZip asSymbolicLink(String name) {
        externalAttributes.put(name, 0xA1FF << 16);
        unixHosted.put(name, true);
        return this;
    }

    /** Marks an entry with the Windows reparse-point attribute, the way a junction or Windows symlink is flagged. */
    FixtureZip asReparsePoint(String name) {
        externalAttributes.put(name, 0x400);
        return this;
    }

    byte[] toBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] archive = bytes.toByteArray();
        patchAttributes(archive);
        return archive;
    }

    /** java.util.zip cannot write attributes, so the central directory records of the marked entries are edited in place. */
    private void patchAttributes(byte[] archive) {
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int end = archive.length - 22;
        while (buffer.getInt(end) != END_OF_CENTRAL_DIRECTORY) {
            end--;
        }
        int count = buffer.getShort(end + 10) & 0xFFFF;
        int position = buffer.getInt(end + 16);
        for (int i = 0; i < count; i++) {
            if (buffer.getInt(position) != CENTRAL_HEADER) {
                throw new IllegalStateException("Corrupt central directory at " + position);
            }
            int nameLength = buffer.getShort(position + 28) & 0xFFFF;
            int extraLength = buffer.getShort(position + 30) & 0xFFFF;
            int commentLength = buffer.getShort(position + 32) & 0xFFFF;
            String name = new String(archive, position + 46, nameLength, StandardCharsets.UTF_8);
            if (externalAttributes.containsKey(name)) {
                buffer.putInt(position + 38, externalAttributes.get(name));
                if (unixHosted.getOrDefault(name, false)) {
                    buffer.putShort(position + 4, (short) (UNIX_HOST | 20));
                }
            }
            position += 46 + nameLength + extraLength + commentLength;
        }
    }
}
