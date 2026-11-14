package dev.ftcsim.acceptance;

import java.util.HashSet;
import java.util.Set;

/**
 * What {@link IdentityHashOrderTest} starts in a JVM of its own: the order in which a hash set of objects that do not
 * override {@code hashCode} is iterated, on a thread that was created after some others, as a robot's thread is.
 */
public final class IdentityHashProbe {
    static final String RESULT = "ORDER> ";

    private IdentityHashProbe() {}

    public static void main(String[] arguments) throws Exception {
        for (int i = 0; i < 3; i++) {
            Thread filler = new Thread(() -> {}, "filler-" + i);
            filler.start();
            filler.join();
        }
        Thread worker = new Thread(IdentityHashProbe::print, "worker");
        worker.start();
        worker.join();
    }

    private static void print() {
        Object[] objects = new Object[16];
        Set<Object> set = new HashSet<>();
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new Object();
            set.add(objects[i]);
        }
        StringBuilder order = new StringBuilder();
        for (Object member : set) {
            for (int i = 0; i < objects.length; i++) {
                if (objects[i] == member) {
                    order.append(i).append(' ');
                }
            }
        }
        System.out.println(RESULT + order.toString().trim());
    }
}
