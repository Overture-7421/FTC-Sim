package dev.ftcsim.hardware.lynx;

import dev.ftcsim.contracts.hardware.HubWire;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The serial bus between the robot controller and its hubs. A datagram goes to the hub it is addressed to; the
 * parent hub is the one on the USB (or embedded) link and the children are reached through it over RS485.
 * Malformed datagrams and datagrams for modules that are not there are ignored, as on a real bus, and recorded.
 *
 * <p>Every exchange first brings all hubs up to the simulated time of the clock, so a read never sees state from
 * before the world's latest step. The host should also call {@link #advanceTo} on every world step, since the hubs
 * keep driving their motors between datagrams.
 *
 * <p>Exchanges and {@link #advanceTo} are the scheduled side: the simulator thread and the robot thread take turns
 * at them. A library's own thread, which the simulation does not schedule, uses {@link #transmitUnscheduled}
 * instead. One lock covers everything the bus holds, so no thread ever sees or leaves a step half done.
 */
public final class LynxBus implements HubWire {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final LongSupplier clockNanos;
    private final BusLatency latency;
    private final List<LynxHub> hubs = new ArrayList<>();
    private final Map<FrameFault, RejectedFrame> rejected = new EnumMap<>(FrameFault.class);
    private long holdOffNanos;

    public LynxBus(LongSupplier clockNanos) {
        this(clockNanos, BusLatency.estimate());
    }

    public LynxBus(LongSupplier clockNanos, BusLatency latency) {
        this.clockNanos = clockNanos;
        this.latency = latency;
    }

    /** Puts a hub on the bus. There is one parent; the addresses of all hubs differ. */
    public void attach(LynxHub hub) {
        lock.lock();
        try {
            if (!isAddressFree(hub.address())) {
                throw new IllegalArgumentException("a hub with address " + hub.address() + " is already on the bus");
            }
            if (hub.isParent() && hubs.stream().anyMatch(LynxHub::isParent)) {
                throw new IllegalArgumentException("the bus already has a parent hub");
            }
            hubs.add(hub);
            hub.useAddressAuthority(this::isAddressFree);
        } finally {
            lock.unlock();
        }
    }

    /** The hubs in order of module address. */
    public List<LynxHub> hubs() {
        lock.lock();
        try {
            return sortedHubs();
        } finally {
            lock.unlock();
        }
    }

    /** Runs every hub up to the simulated time, in address order. */
    public void advanceTo(long nanos) {
        lock.lock();
        try {
            for (LynxHub hub : sortedHubs()) {
                hub.advanceTo(nanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /** The datagrams the bus ignored so far, grouped by reason. */
    public List<RejectedFrame> rejectedFrames() {
        lock.lock();
        try {
            return List.copyOf(rejected.values());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Exchange transmit(byte[] datagram) {
        lock.lock();
        try {
            long now = clockNanos.getAsLong();
            catchUp(now);
            return exchange(datagram, now, Math.max(now, holdOffNanos));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Answers from any thread at any time, as the hubs stand at the time the scheduled side last reached: the hubs are
     * not caught up to the clock, the clock is not read, the hold-off is left alone and the exchange takes no time.
     * Queries change nothing; any other command is executed.
     */
    @Override
    public Exchange transmitUnscheduled(byte[] datagram) {
        lock.lock();
        try {
            return unscheduledExchange(datagram);
        } finally {
            lock.unlock();
        }
    }

    /** {@code sent} is when the host really sends: the clock, or later if the hubs have made the host wait. */
    private Exchange exchange(byte[] datagram, long now, long sent) {
        Datagram request;
        try {
            request = Datagram.parse(datagram);
        } catch (MalformedFrameException malformed) {
            return unanswered(malformed.fault(), now, datagram.length);
        }
        if (request.destination() == Datagram.BROADCAST) {
            return broadcast(request, datagram.length, sent);
        }
        LynxHub hub = reachableHub(request.destination());
        if (hub == null) {
            return unanswered(FrameFault.NO_SUCH_MODULE, now, datagram.length);
        }
        List<Datagram> answers = hub.handle(request, sent);
        List<byte[]> replies = encode(answers);
        long latencyNanos = latency.exchange(!hub.isParent(), datagram.length, size(replies));
        if (isI2cCommand(request)) {
            holdOffNanos = sent + latencyNanos;
        }
        return new Exchange(replies, latencyNanos);
    }

    /** The time the scheduled side last reached, which is what the hubs answer at; the clock is not consulted. */
    private Exchange unscheduledExchange(byte[] datagram) {
        long reached = hubs.stream().mapToLong(LynxHub::nowNanos).max().orElse(0);
        Datagram request;
        try {
            request = Datagram.parse(datagram);
        } catch (MalformedFrameException malformed) {
            noteRejected(malformed.fault(), reached);
            return new Exchange(List.of(), 0);
        }
        List<Datagram> answers;
        if (request.destination() == Datagram.BROADCAST) {
            answers = answeringHubs().flatMap(hub -> hub.handleUnscheduled(request).stream()).toList();
        } else {
            LynxHub hub = reachableHub(request.destination());
            if (hub == null) {
                noteRejected(FrameFault.NO_SUCH_MODULE, reached);
                return new Exchange(List.of(), 0);
            }
            answers = hub.handleUnscheduled(request);
        }
        return new Exchange(encode(answers), 0);
    }

    /**
     * A host that talks to an I2C bus waits for its transactions: it starts one, or is told the bus is busy, and asks
     * again once the answer has arrived. A host that cannot let the clock move meanwhile, as the SDK cannot when it
     * polls inside a lock the world cannot wait for, would keep the transaction from ever finishing. So after an I2C
     * exchange the next datagram is sent no earlier than the end of that exchange, which is when a host that waits
     * sends it anyway.
     */
    private static boolean isI2cCommand(Datagram request) {
        int index = request.packetId() - DekaCommand.BASE;
        DekaCommand[] commands = DekaCommand.values();
        return index >= 0 && index < commands.length && commands[index].isI2c();
    }

    /** Brings every hub up to the clock; a hub that the host has already advanced further stays where it is. */
    private void catchUp(long now) {
        for (LynxHub hub : sortedHubs()) {
            if (hub.nowNanos() < now) {
                hub.advanceTo(now);
            }
        }
    }

    /** Discovery is the one broadcast: every hub answers, the parent first, then the children by address. */
    private Exchange broadcast(Datagram request, int requestBytes, long sent) {
        List<Datagram> answers = new ArrayList<>();
        answeringHubs().forEach(hub -> answers.addAll(hub.handle(request, sent)));
        List<byte[]> replies = encode(answers);
        boolean discovery = request.packetId() == StandardCommand.DISCOVERY.number();
        return new Exchange(
                replies, discovery ? latency.discovery(requestBytes, size(replies)) : latency.unanswered(requestBytes));
    }

    private Stream<LynxHub> answeringHubs() {
        return hubs.stream()
                .filter(this::isReachable)
                .sorted(Comparator.comparing((LynxHub hub) -> !hub.isParent()).thenComparingInt(LynxHub::address));
    }

    private Exchange unanswered(FrameFault fault, long now, int requestBytes) {
        noteRejected(fault, now);
        return new Exchange(List.of(), latency.unanswered(requestBytes));
    }

    private void noteRejected(FrameFault fault, long now) {
        rejected.merge(
                fault,
                new RejectedFrame(fault, now, 1),
                (first, again) -> new RejectedFrame(fault, first.firstSeenNanos(), first.count() + 1));
    }

    private List<LynxHub> sortedHubs() {
        return hubs.stream().sorted(Comparator.comparingInt(LynxHub::address)).toList();
    }

    private LynxHub reachableHub(int address) {
        return hubs.stream().filter(hub -> hub.address() == address && isReachable(hub)).findFirst().orElse(null);
    }

    /** A child can only be reached while the parent it hangs off is on the bus. */
    private boolean isReachable(LynxHub hub) {
        return hub.isParent() || hubs.stream().anyMatch(LynxHub::isParent);
    }

    private boolean isAddressFree(int address) {
        return hubs.stream().noneMatch(hub -> hub.address() == address);
    }

    private static List<byte[]> encode(List<Datagram> datagrams) {
        return datagrams.stream().map(Datagram::encode).toList();
    }

    private static int size(List<byte[]> frames) {
        return frames.stream().mapToInt(frame -> frame.length).sum();
    }
}
