package dev.ftcsim.ui.viewport;

import dev.ftcsim.platform.FramePacer;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.ui.render.OrbitCamera;
import dev.ftcsim.ui.scene.PoseInterpolator;
import dev.ftcsim.ui.scene.SceneModel;
import dev.ftcsim.ui.scene.SnapshotMailbox;
import dev.ftcsim.ui.scene.ViewportFeed;
import dev.ftcsim.ui.stats.FrameTimeRecorder;
import dev.ftcsim.ui.stats.StatSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * The render thread: pace, take the newest snapshot pair, interpolate and hand the poses to a {@link Sink}. It is
 * decoupled from the simulation rate and from the UI toolkit; only the sink knows where pixels go.
 *
 * <p>A frame is drawn only when something it shows has changed: the poses after interpolation, the camera, the scene
 * or the surface. The readback behind the sink is one frame deep, so a few identical frames follow the last change;
 * after that the thread sleeps until {@link #wake()} instead of burning a core to repeat a picture. It draws nothing
 * at all while it is {@linkplain #setSuspended suspended}, for example because the viewport is not on screen.
 */
public final class RenderLoop implements AutoCloseable {
    public static final String INTERVAL = "render.frameInterval";
    public static final String COST = "render.cpuCost";
    public static final String SNAPSHOT_AGE = "render.snapshotAge";
    public static final String PACER_LATENESS = "render.pacerLateness";

    private static final int SETTLE_FRAMES = 2;
    private static final long IDLE_PARK_NANOS = 100_000_000L;
    private static final double MAX_FRAME_SECONDS = 0.05;
    private static final int RECORDER_CAPACITY = 4096;

    /** Mutable per-frame data handed to the sink; valid only during the call. */
    public static final class Frame {
        public PoseInterpolator.Poses poses;
        public long startNanos;
        public long snapshotPublishedNanos;
        public long index;
    }

    /** Where a frame goes. All methods run on the render thread. */
    public interface Sink {
        default void attach() throws Exception {}

        /** Whether another frame is needed although the world did not change, for example after a resize. */
        default boolean wantsFrame() {
            return false;
        }

        /** Draws one frame; returns false when nothing could be drawn yet, for example before the surface exists. */
        boolean draw(Frame frame) throws Exception;

        default void detach() {}
    }

    private final ViewportFeed feed;
    private final Sink sink;
    private final OrbitCamera camera;
    private final StatSet stats;
    private final FrameTimeRecorder intervals;
    private final FrameTimeRecorder costs;
    private final FrameTimeRecorder ages;
    private final FrameTimeRecorder lateness;
    private final CountDownLatch firstFrame = new CountDownLatch(1);
    private final AtomicReference<FramePacer> nextPacer = new AtomicReference<>();
    private FramePacer pacer;
    private volatile boolean running;
    private volatile boolean suspended;
    private volatile boolean redrawRequested;
    private volatile Throwable failure;
    private volatile long framesDrawn;
    private volatile Thread thread;
    private volatile Consumer<Throwable> failureListener = failed -> {};

    /** @param pacer used by the render thread only; if it is {@link AutoCloseable} the loop closes it when done with it */
    public RenderLoop(ViewportFeed feed, Sink sink, FramePacer pacer, OrbitCamera camera, StatSet stats) {
        this.feed = feed;
        this.sink = sink;
        this.pacer = pacer;
        this.camera = camera;
        this.stats = stats;
        this.intervals = stats.recorder(INTERVAL, RECORDER_CAPACITY);
        this.costs = stats.recorder(COST, RECORDER_CAPACITY);
        this.ages = stats.recorder(SNAPSHOT_AGE, RECORDER_CAPACITY);
        this.lateness = stats.recorder(PACER_LATENESS, RECORDER_CAPACITY);
    }

    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        running = true;
        Thread created = new Thread(this::run, "render-loop");
        created.setDaemon(true);
        created.setPriority(Thread.NORM_PRIORITY + 2);
        thread = created;
        created.start();
    }

    /** Called once, from the render thread, with whatever stopped the loop. */
    public void onFailure(Consumer<Throwable> listener) {
        failureListener = listener;
    }

    /** Makes a sleeping loop look again at once; any thread, cheap enough to call for every new snapshot. */
    public void wake() {
        Thread target = thread;
        if (target != null) {
            LockSupport.unpark(target);
        }
    }

    /**
     * Stops drawing while the picture is not shown, or starts again. Resuming draws a frame at once even though
     * nothing changed, because whatever showed the last frame may have forgotten it.
     */
    public void setSuspended(boolean value) {
        suspended = value;
        if (!value) {
            requestRedraw();
        }
    }

    /** Draws the next frame although nothing changed; any thread. */
    public void requestRedraw() {
        redrawRequested = true;
        wake();
    }

    /**
     * Takes {@code next} into use before the next frame, for instance to change the frame rate. The pacer it replaces
     * is closed on the render thread, when it implements {@link AutoCloseable}, because only that thread may use it.
     */
    public void replacePacer(FramePacer next) {
        retire(nextPacer.getAndSet(next));
        wake();
    }

    private static void retire(FramePacer unused) {
        if (unused instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Releasing a timer handle has nothing a caller could do about it.
            }
        }
    }

    /** Blocks until a frame has been drawn; false on timeout or when the loop failed first. */
    public boolean awaitFirstFrame(long timeoutMillis) throws InterruptedException {
        return firstFrame.await(timeoutMillis, TimeUnit.MILLISECONDS) && failure == null;
    }

    public Throwable failure() {
        return failure;
    }

    public long framesDrawn() {
        return framesDrawn;
    }

    @Override
    public void close() {
        Thread joined;
        synchronized (this) {
            running = false;
            joined = thread;
        }
        if (joined == null) {
            return;
        }
        LockSupport.unpark(joined);
        try {
            joined.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            thread = null;
        }
    }

    private void run() {
        try {
            sink.attach();
            loop();
        } catch (Throwable t) {
            fail(t);
        } finally {
            retire(pacer);
            retire(nextPacer.getAndSet(null));
            try {
                sink.detach();
            } catch (Throwable t) {
                fail(t);
            }
        }
    }

    private void fail(Throwable t) {
        if (failure == null) {
            failure = t;
            failureListener.accept(t);
        }
        firstFrame.countDown();
    }

    private void loop() throws Exception {
        PoseInterpolator.Poses poses = new PoseInterpolator.Poses();
        PoseInterpolator.Poses drawnPoses = new PoseInterpolator.Poses();
        Frame frame = new Frame();
        frame.poses = poses;
        SceneModel drawnScene = null;
        SceneModel obstacleScene = null;
        long drawnCamera = -1;
        int settle = 0;
        long previousStart = 0;
        while (running) {
            FramePacer replacement = nextPacer.getAndSet(null);
            if (replacement != null) {
                retire(pacer);
                pacer = replacement;
            }
            long late = pacer.awaitNextFrame();
            long start = System.nanoTime();
            SceneModel scene = feed.scene();
            if (scene == null || suspended) {
                rest();
                continue;
            }
            if (scene != obstacleScene) {
                camera.setObstacles(scene);
                obstacleScene = scene;
            }
            SnapshotMailbox.Latest latest = feed.mailbox().latest();
            if (latest == null) {
                poses.count = 0;
            } else {
                PoseInterpolator.interpolate(latest, PoseInterpolator.alpha(latest, start), poses);
            }
            double dt = previousStart == 0 ? 0.0 : Math.min(MAX_FRAME_SECONDS, (start - previousStart) / 1e9);
            followRobot(poses, dt);
            long cameraVersion = camera.version();
            boolean forced = redrawRequested;
            boolean changed = scene != drawnScene || cameraVersion != drawnCamera || !poses.sameAs(drawnPoses)
                    || forced || sink.wantsFrame();
            if (changed) {
                settle = SETTLE_FRAMES;
            } else if (settle > 0) {
                settle--;
            } else {
                rest();
                continue;
            }
            frame.startNanos = start;
            frame.snapshotPublishedNanos = latest == null ? start : latest.currentNanos();
            if (!sink.draw(frame)) {
                continue;
            }
            if (forced) {
                redrawRequested = false;
            }
            drawnScene = scene;
            drawnPoses.copyFrom(poses);
            drawnCamera = cameraVersion;
            previousStart = start;
            frame.index++;
            long end = System.nanoTime();
            framesDrawn = frame.index;
            if (stats.recording()) {
                intervals.markFrame(start);
                costs.record(end - start);
                if (latest != null) {
                    ages.record(start - latest.currentNanos());
                }
                lateness.record(late);
            } else {
                intervals.breakSequence();
            }
            firstFrame.countDown();
        }
    }

    /** Sleeps until woken or for a tenth of a second; what follows the sleep is not part of a frame interval. */
    private void rest() {
        intervals.breakSequence();
        LockSupport.parkNanos(this, IDLE_PARK_NANOS);
    }

    private void followRobot(PoseInterpolator.Poses poses, double dtSeconds) {
        int robot = poses.indexOfKind(BodyKinds.CHASSIS);
        if (robot >= 0) {
            int offset = robot * PoseInterpolator.POSE_STRIDE;
            camera.track(poses.values[offset], poses.values[offset + 1], poses.yaw(robot), dtSeconds);
        }
    }
}
