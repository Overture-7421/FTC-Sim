package dev.ftcsim.contracts.hooks;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;

/**
 * A {@code SecureRandom} whose numbers follow from its seed: the robot world's randomness is the host's seed and nothing
 * the operating system knows. It has the classes and methods of a {@code SecureRandom}, so robot code that is handed one
 * cannot tell, except by being able to repeat what it did.
 */
final class SimulatedSecureRandom extends SecureRandom {
    private static final Provider PROVIDER = new Provider("FTC-Sim", "1.0", "random numbers that follow from the simulator's seed") {};

    private final String algorithm;

    SimulatedSecureRandom(String algorithm, long seed) {
        super(new Engine(seed), PROVIDER);
        this.algorithm = algorithm;
    }

    @Override
    public String getAlgorithm() {
        return algorithm;
    }

    /** A 64-bit mixing generator; setting a seed folds the bytes into what it has, as {@code SecureRandom} supplements. */
    private static final class Engine extends SecureRandomSpi {
        private static final long serialVersionUID = 1L;
        private static final long GAMMA = 0x9E3779B97F4A7C15L;

        private long state;

        Engine(long seed) {
            state = seed;
        }

        private long next() {
            long z = state += GAMMA;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        @Override
        protected synchronized void engineSetSeed(byte[] seed) {
            long folded = state;
            for (byte value : seed) {
                folded = (folded ^ (value & 0xFF)) * 0x100000001B3L;
            }
            state = folded;
        }

        @Override
        protected synchronized void engineNextBytes(byte[] bytes) {
            int index = 0;
            while (index < bytes.length) {
                long word = next();
                for (int shift = 0; shift < 64 && index < bytes.length; shift += 8) {
                    bytes[index++] = (byte) (word >>> shift);
                }
            }
        }

        @Override
        protected byte[] engineGenerateSeed(int numBytes) {
            if (numBytes < 0) {
                throw new IllegalArgumentException("numBytes cannot be negative");
            }
            byte[] bytes = new byte[numBytes];
            engineNextBytes(bytes);
            return bytes;
        }
    }
}
