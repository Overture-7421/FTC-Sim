package dalvik.system;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The class directory of a code file: the APK the application was installed from, whose {@code classes.dex},
 * {@code classes2.dex} and so on define every class of the app. The SDK enumerates the names to find OpModes and
 * hooks; nothing is loaded or initialised. Only the class definitions are read, which is all a directory of
 * names needs.
 */
public final class DexFile {
    private static final int STRING_IDS_OFFSET = 60;
    private static final int TYPE_IDS_SIZE = 64;
    private static final int TYPE_IDS_OFFSET = 68;
    private static final int CLASS_DEFS_SIZE = 96;
    private static final int CLASS_DEFS_OFFSET = 100;
    private static final int CLASS_DEF_BYTES = 32;

    private final List<String> classNames = new ArrayList<>();

    public DexFile(String apkPath) throws IOException {
        try (ZipFile apk = new ZipFile(apkPath)) {
            for (ZipEntry entry : Collections.list(apk.entries())) {
                if (entry.getName().matches("classes\\d*\\.dex")) {
                    byte[] dex = apk.getInputStream(entry).readAllBytes();
                    readClassNames(ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN));
                }
            }
        }
    }

    public Enumeration<String> entries() {
        return Collections.enumeration(classNames);
    }

    public void close() {}

    private void readClassNames(ByteBuffer dex) throws IOException {
        if (dex.limit() < CLASS_DEFS_OFFSET + 4 || dex.get(0) != 'd' || dex.get(1) != 'e' || dex.get(2) != 'x') {
            throw new IOException("Not a dex file");
        }
        int stringIds = dex.getInt(STRING_IDS_OFFSET);
        int typeIds = dex.getInt(TYPE_IDS_OFFSET);
        int classDefs = dex.getInt(CLASS_DEFS_OFFSET);
        int classCount = dex.getInt(CLASS_DEFS_SIZE);
        if (classCount > 0 && dex.getInt(TYPE_IDS_SIZE) == 0) {
            throw new IOException("A dex file defines classes of types it does not list");
        }
        for (int i = 0; i < classCount; i++) {
            int type = dex.getInt(classDefs + i * CLASS_DEF_BYTES);
            int string = dex.getInt(typeIds + 4 * type);
            String descriptor = readString(dex, dex.getInt(stringIds + 4 * string));
            if (descriptor.length() > 2 && descriptor.charAt(0) == 'L' && descriptor.endsWith(";")) {
                classNames.add(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
            }
        }
    }

    /** A string item: a ULEB128 length in UTF-16 code units, then modified UTF-8 up to a zero. */
    private static String readString(ByteBuffer dex, int offset) {
        int position = offset;
        while ((dex.get(position++) & 0x80) != 0) {
            // the length is redundant given the terminator
        }
        StringBuilder text = new StringBuilder();
        for (int b = dex.get(position++) & 0xFF; b != 0; b = dex.get(position++) & 0xFF) {
            if (b < 0x80) {
                text.append((char) b);
            } else if ((b & 0xE0) == 0xC0) {
                text.append((char) (((b & 0x1F) << 6) | (dex.get(position++) & 0x3F)));
            } else {
                int second = dex.get(position++) & 0x3F;
                int third = dex.get(position++) & 0x3F;
                text.append((char) (((b & 0x0F) << 12) | (second << 6) | third));
            }
        }
        return text.toString();
    }
}
