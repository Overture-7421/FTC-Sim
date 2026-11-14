package dev.ftcsim.contracts.hardware;

/**
 * Anything that counts the way a quadrature encoder does: a motor's own encoder, a dead wheel's, an absolute
 * channel unwrapped into a running count. The sign is the sign of the count, whatever turns it.
 */
public interface EncoderSource {

    /** The count since the source was created. */
    long encoderCounts();

    /** The rate of change of the count, in counts per second, signed. */
    double encoderCountsPerSecond();
}
