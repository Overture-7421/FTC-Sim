package dev.ftcsim.contracts.hooks;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomParameters;
import java.security.Security;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Entry points that rewritten robot bytecode calls instead of the JDK's ways of getting numbers nobody can predict: a
 * {@code Random} nobody gave a seed, {@code Math.random}, {@code UUID.randomUUID}, a {@code SecureRandom}, the shuffle of
 * a list with no generator, {@code ThreadLocalRandom}. Each takes its seed from the installed hooks, so that a program
 * gets the same numbers on every run with the same seed, whatever the real time and whatever the machine.
 *
 * <p>A generator that robot code seeds itself, such as {@code new Random(42)}, is the program's own business and is left
 * alone.
 */
public final class RandomHooks {
    /** The algorithms {@code SecureRandom.getInstance} knows on the platforms the simulator runs on, in capitals. */
    private static final Set<String> ALGORITHMS = Set.of(
            "SHA1PRNG", "DRBG", "NATIVEPRNG", "NATIVEPRNGBLOCKING", "NATIVEPRNGNONBLOCKING", "WINDOWS-PRNG");
    private static final String STRONG_ALGORITHM = "DRBG";

    private RandomHooks() {}

    public static Random newRandom() {
        return new Random(Hooks.randomSeed());
    }

    public static SplittableRandom newSplittableRandom() {
        return new SplittableRandom(Hooks.randomSeed());
    }

    public static double mathRandom() {
        return Hooks.mathRandom();
    }

    public static UUID randomUUID() {
        Random random = newRandom();
        long most = random.nextLong();
        long least = random.nextLong();
        most = (most & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    public static void shuffle(List<?> list) {
        Collections.shuffle(list, newRandom());
    }

    /** {@code ThreadLocalRandom.current()}, whose state in the calling thread follows from the seed. */
    public static ThreadLocalRandom threadLocalRandom() {
        return ThreadLocalSeeder.current();
    }

    // SecureRandom

    public static SecureRandom newSecureRandom() {
        return new SimulatedSecureRandom("unknown", Hooks.randomSeed());
    }

    public static SecureRandom newSecureRandom(byte[] seed) {
        SecureRandom random = newSecureRandom();
        random.setSeed(seed);
        return random;
    }

    public static SecureRandom getInstance(String algorithm) throws NoSuchAlgorithmException {
        return instance(algorithm);
    }

    public static SecureRandom getInstance(String algorithm, String provider) throws NoSuchAlgorithmException, NoSuchProviderException {
        if (provider == null || provider.isEmpty()) {
            throw new IllegalArgumentException("missing provider");
        }
        if (Security.getProvider(provider) == null) {
            throw new NoSuchProviderException("no such provider: " + provider);
        }
        return instance(algorithm);
    }

    public static SecureRandom getInstance(String algorithm, Provider provider) throws NoSuchAlgorithmException {
        if (provider == null) {
            throw new IllegalArgumentException("missing provider");
        }
        return instance(algorithm);
    }

    public static SecureRandom getInstance(String algorithm, SecureRandomParameters parameters) throws NoSuchAlgorithmException {
        return instance(algorithm);
    }

    public static SecureRandom getInstance(String algorithm, SecureRandomParameters parameters, String provider)
            throws NoSuchAlgorithmException, NoSuchProviderException {
        return getInstance(algorithm, provider);
    }

    public static SecureRandom getInstance(String algorithm, SecureRandomParameters parameters, Provider provider)
            throws NoSuchAlgorithmException {
        return getInstance(algorithm, provider);
    }

    public static SecureRandom getInstanceStrong() {
        return new SimulatedSecureRandom(STRONG_ALGORITHM, Hooks.randomSeed());
    }

    public static byte[] getSeed(int numBytes) {
        return new SimulatedSecureRandom("unknown", Hooks.randomSeed()).generateSeed(numBytes);
    }

    private static SecureRandom instance(String algorithm) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        if (!ALGORITHMS.contains(algorithm.toUpperCase(Locale.ROOT))) {
            throw new NoSuchAlgorithmException(algorithm + " SecureRandom not available");
        }
        return new SimulatedSecureRandom(algorithm, Hooks.randomSeed());
    }
}
