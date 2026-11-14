package dev.ftcsim.physics;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Sha256 {

    private Sha256() {}

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK provides SHA-256", e);
        }
    }

    static String hex(byte[] bytes) {
        byte[] hash = newDigest().digest(bytes);
        return HexFormat.of().formatHex(hash);
    }
}
