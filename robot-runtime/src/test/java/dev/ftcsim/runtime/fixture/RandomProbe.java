package dev.ftcsim.runtime.fixture;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Every way a program gets numbers nobody can predict, and two ways of getting numbers that are its own. */
public final class RandomProbe implements Callable<List<String>> {
    /** A generator the program makes by extending {@code Random}, whose constructor reaches {@code Random()} through {@code super()}. */
    public static final class Dice extends Random {
        private static final long serialVersionUID = 1L;

        public Dice() {
            super();
        }
    }

    @Override
    public List<String> call() throws Exception {
        List<String> seen = new ArrayList<>();
        seen.add("Random " + new Random().nextLong());
        seen.add("Random subclass " + new Dice().nextLong());
        Supplier<Random> reference = Random::new;
        seen.add("Random::new " + reference.get().nextLong());
        seen.add("SplittableRandom " + new SplittableRandom().nextLong());
        seen.add("Math.random " + Math.random());
        seen.add("StrictMath.random " + StrictMath.random());
        seen.add("UUID " + UUID.randomUUID());
        List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        Collections.shuffle(list);
        seen.add("shuffle " + list);
        seen.add("ThreadLocalRandom " + ThreadLocalRandom.current().nextLong());
        seen.add("SecureRandom " + new SecureRandom().nextLong());
        seen.add("SecureRandom(seed) " + new SecureRandom(new byte[] {1, 2, 3}).nextLong());
        seen.add("SecureRandom.getInstance " + SecureRandom.getInstance("SHA1PRNG").nextLong());
        seen.add("SecureRandom strong " + SecureRandom.getInstanceStrong().nextLong());
        seen.add("SecureRandom.getSeed " + Arrays.toString(SecureRandom.getSeed(4)));
        // The program's own seeds are its own business and give what they give anywhere.
        seen.add("own Random " + new Random(42).nextLong());
        List<Integer> own = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        Collections.shuffle(own, new Random(7));
        seen.add("own shuffle " + own);
        return seen;
    }
}
