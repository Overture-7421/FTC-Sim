package dev.ftcsim.ui.viewport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.platform.FramePacer;
import dev.ftcsim.ui.render.OrbitCamera;
import dev.ftcsim.ui.scene.SceneModel;
import dev.ftcsim.ui.scene.ViewportFeed;
import dev.ftcsim.ui.stats.StatSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs the render loop one frame at a time under a pacer that waits for the test, so nothing depends on real time. */
class RenderLoopTest {
    private static final long WAIT_SECONDS = 10;

    /** Lets the loop run exactly one iteration per {@link #step}. */
    private static final class StepPacer implements FramePacer, AutoCloseable {
        private final Semaphore turns = new Semaphore(0);
        private final Semaphore waiting = new Semaphore(0);
        final AtomicInteger closes = new AtomicInteger();
        volatile Thread closedBy;

        @Override
        public long awaitNextFrame() {
            waiting.release();
            turns.acquireUninterruptibly();
            return 0L;
        }

        @Override
        public void close() {
            closedBy = Thread.currentThread();
            closes.incrementAndGet();
        }

        void awaitLoop() throws InterruptedException {
            assertTrue(waiting.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS), "the loop never asked for a frame");
        }

        /** Grants one iteration and returns once the loop asks for the next; a resting loop is woken until it does. */
        void step(RenderLoop loop) throws InterruptedException {
            turns.release();
            for (int tries = 0; !waiting.tryAcquire(5, TimeUnit.MILLISECONDS); tries++) {
                assertTrue(tries < WAIT_SECONDS * 200, "the loop never finished its iteration");
                loop.wake();
            }
        }

        /** Lets a loop that is waiting for a turn run to its end. */
        void release() {
            turns.release(1_000);
        }
    }

    private static final class CountingSink implements RenderLoop.Sink {
        final AtomicInteger draws = new AtomicInteger();
        final AtomicInteger poseCount = new AtomicInteger();
        final AtomicInteger attached = new AtomicInteger();
        final AtomicInteger detached = new AtomicInteger();
        volatile boolean wantsFrame;
        volatile RuntimeException failure;

        @Override
        public void attach() {
            attached.incrementAndGet();
        }

        @Override
        public boolean wantsFrame() {
            return wantsFrame;
        }

        @Override
        public boolean draw(RenderLoop.Frame frame) {
            if (failure != null) {
                throw failure;
            }
            poseCount.set(frame.poses.count);
            draws.incrementAndGet();
            return true;
        }

        @Override
        public void detach() {
            detached.incrementAndGet();
        }
    }

    private final ViewportFeed feed = new ViewportFeed();
    private final OrbitCamera camera = new OrbitCamera();
    private final StepPacer pacer = new StepPacer();
    private final CountingSink sink = new CountingSink();
    private final RenderLoop loop = new RenderLoop(feed, sink, pacer, camera, new StatSet());

    @AfterEach
    void stop() {
        pacer.release();
        loop.close();
    }

    private void startLoop() throws InterruptedException {
        loop.start();
        pacer.awaitLoop();
    }

    private void rest() throws InterruptedException {
        for (int i = 0; i < 6; i++) {
            pacer.step(loop);
        }
    }

    private static WorldSnapshot snapshot(long step) {
        return new WorldSnapshot(step, step * 4_000_000L, List.of(new BodyState(1, 1, 0f, 0f, 0f, 0f, 0f, 0f, 1f)));
    }

    @Test
    void nothingIsDrawnWithoutASceneAndAChangeDrawsAFewFramesThenTheLoopRests() throws Exception {
        startLoop();
        pacer.step(loop);
        assertEquals(0, sink.draws.get(), "no scene yet");

        feed.show(SceneModel.of(null, null));
        for (int i = 0; i < 10; i++) {
            pacer.step(loop);
        }

        assertEquals(3, sink.draws.get(), "the changed frame and two more to flush the readback, then nothing");
    }

    @Test
    void aNewSnapshotIsDrawnAfterTheLoopHasRested() throws Exception {
        feed.show(SceneModel.of(null, null));
        startLoop();
        rest();
        int resting = sink.draws.get();

        feed.publish(snapshot(1));
        pacer.step(loop);

        assertTrue(sink.draws.get() > resting);
        assertEquals(1, sink.poseCount.get(), "the snapshot's body was handed to the sink");
    }

    @Test
    void aSnapshotThatShowsTheSameWorldIsNotDrawnAgain() throws Exception {
        feed.show(SceneModel.of(null, null));
        feed.publish(snapshot(1));
        startLoop();
        rest();
        int resting = sink.draws.get();

        feed.publish(snapshot(2));
        pacer.step(loop);
        pacer.step(loop);

        assertEquals(resting, sink.draws.get(), "step 2 has the body where step 1 had it");
    }

    @Test
    void movingTheCameraOrAskingForAFrameIsDrawnToo() throws Exception {
        feed.show(SceneModel.of(null, null));
        startLoop();
        rest();
        int resting = sink.draws.get();

        camera.orbit(5, 0);
        pacer.step(loop);
        int afterCamera = sink.draws.get();
        rest();
        int rested = sink.draws.get();
        sink.wantsFrame = true;
        pacer.step(loop);

        assertTrue(afterCamera > resting);
        assertEquals(afterCamera + 2, rested, "two frames to flush after the camera moved, then rest");
        assertTrue(sink.draws.get() > rested);
    }

    @Test
    void aSuspendedLoopDrawsNothingAndResumingDrawsAFrameAtOnceEvenThoughNothingChanged() throws Exception {
        feed.show(SceneModel.of(null, null));
        startLoop();
        rest();
        int resting = sink.draws.get();

        loop.setSuspended(true);
        feed.publish(snapshot(1));
        pacer.step(loop);
        pacer.step(loop);
        assertEquals(resting, sink.draws.get(), "nothing is drawn while the picture is not shown");

        loop.setSuspended(false);
        pacer.step(loop);

        assertTrue(sink.draws.get() > resting);
        assertEquals(1, sink.poseCount.get(), "what happened meanwhile is drawn");
    }

    @Test
    void aRequestedRedrawIsDrawnOnceAndThenTheLoopRestsAgain() throws Exception {
        feed.show(SceneModel.of(null, null));
        startLoop();
        rest();
        int resting = sink.draws.get();

        loop.requestRedraw();
        pacer.step(loop);
        rest();
        int redrawn = sink.draws.get();
        pacer.step(loop);

        assertTrue(redrawn > resting);
        assertEquals(redrawn, sink.draws.get());
    }

    @Test
    void aReplacementPacerIsUsedFromTheNextFrameAndTheOldOneIsClosedOnTheRenderThread() throws Exception {
        feed.show(SceneModel.of(null, null));
        startLoop();
        pacer.step(loop);
        StepPacer second = new StepPacer();

        loop.replacePacer(second);
        pacer.turns.release();
        second.awaitLoop();

        assertEquals(1, pacer.closes.get());
        assertNotNull(pacer.closedBy);
        assertNotSame(Thread.currentThread(), pacer.closedBy, "only the render thread may use a pacer, so only it closes it");
        second.release();
    }

    @Test
    void aSinkThatFailsStopsTheLoopAndTellsTheListenerOnce() throws Exception {
        AtomicReference<Throwable> heard = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        loop.onFailure(failure -> {
            heard.set(failure);
            calls.incrementAndGet();
        });
        feed.show(SceneModel.of(null, null));
        sink.failure = new IllegalStateException("no GL");
        startLoop();

        pacer.release();

        assertFalse(loop.awaitFirstFrame(WAIT_SECONDS * 1000));
        loop.close();
        assertSame(sink.failure, loop.failure());
        assertSame(sink.failure, heard.get());
        assertEquals(1, calls.get());
        assertEquals(1, sink.detached.get(), "the sink is released even though it failed");
    }

    @Test
    void aFrameIsReportedAndClosingDetachesTheSink() throws Exception {
        feed.show(SceneModel.of(null, null));
        startLoop();
        pacer.step(loop);

        assertTrue(loop.awaitFirstFrame(WAIT_SECONDS * 1000));
        assertEquals(1, sink.attached.get());
        assertEquals(1L, loop.framesDrawn());
        pacer.release();
        loop.close();
        assertEquals(1, sink.detached.get());
    }
}
