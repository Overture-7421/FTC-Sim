package dev.ftcsim.hardware.device.digitalchickenlabs;

/** The PROFIBUS CRC-16 the OctoQuad protects its bulk data with: polynomial 0x1DCF, initial value and final XOR 0xFFFF. */
final class Crc16Profibus {

    private static final int POLYNOMIAL = 0x1DCF;

    private Crc16Profibus() {}

    static int of(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ POLYNOMIAL : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc ^ 0xFFFF;
    }
}
