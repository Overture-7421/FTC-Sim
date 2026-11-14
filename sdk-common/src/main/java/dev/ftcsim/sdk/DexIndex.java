package dev.ftcsim.sdk;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.zip.Adler32;

/**
 * Writes a {@code classes.dex} that lists classes and holds nothing else: an empty public class definition for
 * each name, with no superclass, members or code. Tools that enumerate the classes of an application read such a
 * file as they read any other; the classes themselves are loaded from the robot program's class path, never
 * from here. The layout follows the published Dalvik executable format, version 035.
 */
final class DexIndex {
    private static final int HEADER_SIZE = 0x70;
    private static final int CLASS_DEF_SIZE = 32;
    private static final int ENDIAN_TAG = 0x12345678;
    private static final int NO_INDEX = 0xFFFFFFFF;
    private static final int ACC_PUBLIC = 0x1;
    private static final int TYPE_HEADER_ITEM = 0x0000;
    private static final int TYPE_STRING_ID_ITEM = 0x0001;
    private static final int TYPE_TYPE_ID_ITEM = 0x0002;
    private static final int TYPE_CLASS_DEF_ITEM = 0x0006;
    private static final int TYPE_MAP_LIST = 0x1000;
    private static final int TYPE_STRING_DATA_ITEM = 0x2002;

    private DexIndex() {}

    /** @param classNames binary class names such as {@code org.example.Robot$Arm}, in any order and with repeats */
    static byte[] of(Collection<String> classNames) {
        // Strings, and with them types, are kept in the order of their UTF-16 code units, as the format demands.
        List<String> descriptors = classNames.stream().map(name -> "L" + name.replace('.', '/') + ';').sorted().distinct().toList();
        int count = descriptors.size();

        int stringIdsOffset = count == 0 ? 0 : HEADER_SIZE;
        int typeIdsOffset = count == 0 ? 0 : stringIdsOffset + 4 * count;
        int classDefsOffset = count == 0 ? 0 : typeIdsOffset + 4 * count;
        int dataOffset = HEADER_SIZE + count * (4 + 4 + CLASS_DEF_SIZE);

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] stringOffsets = new int[count];
        for (int i = 0; i < count; i++) {
            stringOffsets[i] = dataOffset + data.size();
            writeString(data, descriptors.get(i));
        }
        int mapOffset = align4(dataOffset + data.size());
        int mapItems = count == 0 ? 2 : 6;
        int fileSize = mapOffset + 4 + 12 * mapItems;

        ByteBuffer file = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        file.put(new byte[] {'d', 'e', 'x', '\n', '0', '3', '5', 0});
        file.position(32);
        file.putInt(fileSize).putInt(HEADER_SIZE).putInt(ENDIAN_TAG);
        file.putInt(0).putInt(0); // link section
        file.putInt(mapOffset);
        file.putInt(count).putInt(stringIdsOffset);
        file.putInt(count).putInt(typeIdsOffset);
        file.putInt(0).putInt(0); // prototypes
        file.putInt(0).putInt(0); // fields
        file.putInt(0).putInt(0); // methods
        file.putInt(count).putInt(classDefsOffset);
        file.putInt(fileSize - dataOffset).putInt(dataOffset);

        for (int offset : stringOffsets) {
            file.putInt(offset);
        }
        for (int i = 0; i < count; i++) {
            file.putInt(i);
        }
        for (int i = 0; i < count; i++) {
            file.putInt(i).putInt(ACC_PUBLIC).putInt(NO_INDEX).putInt(0).putInt(NO_INDEX).putInt(0).putInt(0).putInt(0);
        }
        file.put(data.toByteArray());
        file.position(mapOffset);
        file.putInt(mapItems);
        putMapItem(file, TYPE_HEADER_ITEM, 1, 0);
        if (count > 0) {
            putMapItem(file, TYPE_STRING_ID_ITEM, count, stringIdsOffset);
            putMapItem(file, TYPE_TYPE_ID_ITEM, count, typeIdsOffset);
            putMapItem(file, TYPE_CLASS_DEF_ITEM, count, classDefsOffset);
            putMapItem(file, TYPE_STRING_DATA_ITEM, count, dataOffset);
        }
        putMapItem(file, TYPE_MAP_LIST, 1, mapOffset);

        byte[] bytes = file.array();
        sign(bytes);
        return bytes;
    }

    private static void putMapItem(ByteBuffer file, int type, int size, int offset) {
        file.putShort((short) type).putShort((short) 0).putInt(size).putInt(offset);
    }

    private static int align4(int offset) {
        return (offset + 3) & ~3;
    }

    /** A string item: its length in UTF-16 code units as a ULEB128, its characters in modified UTF-8, and a zero. */
    private static void writeString(ByteArrayOutputStream out, String value) {
        for (int length = value.length(); ; length >>>= 7) {
            if (length < 0x80) {
                out.write(length);
                break;
            }
            out.write((length & 0x7F) | 0x80);
        }
        for (char c : value.toCharArray()) {
            if (c != 0 && c < 0x80) {
                out.write(c);
            } else if (c < 0x800) {
                out.write(0xC0 | (c >> 6));
                out.write(0x80 | (c & 0x3F));
            } else {
                out.write(0xE0 | (c >> 12));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        }
        out.write(0);
    }

    /** The signature covers everything after itself, then the checksum covers everything after the checksum. */
    private static void sign(byte[] file) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(file, 32, file.length - 32);
            System.arraycopy(sha1.digest(), 0, file, 12, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-1", e);
        }
        Adler32 checksum = new Adler32();
        checksum.update(file, 12, file.length - 12);
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(8, (int) checksum.getValue());
    }
}
