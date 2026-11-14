package dev.ftcsim.runtime.fixture;

import java.util.concurrent.Callable;

/** Every way a loop repeats: a counting loop, a condition loop and a loop that tests at the bottom. */
public final class LoopProbe implements Callable<Integer> {
    @Override
    public Integer call() {
        int total = 0;
        for (int i = 0; i < 3; i++) {
            total += i;
        }
        int j = 0;
        while (j < 4) {
            j++;
        }
        int k = 0;
        do {
            k++;
        } while (k < 2);
        return total + j + k;
    }
}
