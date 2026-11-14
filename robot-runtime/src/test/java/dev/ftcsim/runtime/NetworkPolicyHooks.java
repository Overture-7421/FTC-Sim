package dev.ftcsim.runtime;

import dev.ftcsim.contracts.hooks.NetworkRequest;
import dev.ftcsim.contracts.hooks.RobotHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Real clock, and a network policy that remembers every request and allows the ones the test chooses. Listeners are
 * left to that policy unless the test asks for them to be simulated, as the hosts' default is.
 */
final class NetworkPolicyHooks implements RobotHooks {
    final List<String> requests = new ArrayList<>();
    private final Predicate<NetworkRequest> allowed;
    private final boolean simulateListeners;

    NetworkPolicyHooks(Predicate<NetworkRequest> allowed) {
        this(allowed, false);
    }

    NetworkPolicyHooks(Predicate<NetworkRequest> allowed, boolean simulateListeners) {
        this.allowed = allowed;
        this.simulateListeners = simulateListeners;
    }

    @Override
    public boolean allowNetwork(NetworkRequest request) {
        requests.add(request.kind() + " " + request.target());
        return allowed.test(request);
    }

    @Override
    public boolean simulateListeners() {
        return simulateListeners;
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Override
    public void sleep(long millis, int nanos) throws InterruptedException {
        Thread.sleep(millis, nanos);
    }

    @Override
    public void sleep(TimeUnit unit, long duration) throws InterruptedException {
        unit.sleep(duration);
    }

    @Override
    public void yieldThread() {
        Thread.yield();
    }

    @Override
    public void loadLibrary(String name) {}

    @Override
    public void load(String absolutePath) {}
}
