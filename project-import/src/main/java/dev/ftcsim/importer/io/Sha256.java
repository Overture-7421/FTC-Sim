package dev.ftcsim.importer.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** SHA-256 as 64 lower-case hexadecimal digits, the one form every manifest of this module uses. */
public final class Sha256 {
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private Sha256() {}

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime provides SHA-256", e);
        }
    }

    public static String hex(MessageDigest digest) {
        return HEX.formatHex(digest.digest());
    }

    public static String of(byte[] data) {
        return HEX.formatHex(newDigest().digest(data));
    }

    public static String of(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest);
    }

    /** Whether the text is a digest as {@link #of} writes it. */
    public static boolean isDigest(String text) {
        return DIGEST.matcher(text).matches();
    }
}
