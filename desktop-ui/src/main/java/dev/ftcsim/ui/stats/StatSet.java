package dev.ftcsim.ui.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named {@link FrameTimeRecorder}s. Each recorder has exactly one writer thread; writers check {@link #recording()}
 * first, so nothing is collected while nobody looks. Starting to record forgets what an earlier recording left.
 */
public final class StatSet {
    private final Map<String, FrameTimeRecorder> recorders = new LinkedHashMap<>();
    private volatile boolean recording;

    /** Registers a recorder; call before the writer thread starts. */
    public synchronized FrameTimeRecorder recorder(String name, int capacity) {
        return recorders.computeIfAbsent(name, key -> new FrameTimeRecorder(capacity));
    }

    public boolean recording() {
        return recording;
    }

    public synchronized void startRecording() {
        if (!recording) {
            recorders.values().forEach(FrameTimeRecorder::clear);
            recording = true;
        }
    }

    public void stopRecording() {
        recording = false;
    }

    /** Summaries in registration order; recorders that saw no samples are omitted. */
    public synchronized Map<String, Summary> summaries() {
        Map<String, Summary> result = new LinkedHashMap<>();
        recorders.forEach((name, recorder) -> {
            Summary summary = Summary.ofNanos(recorder.copyIntervals());
            if (summary.count() > 0) {
                result.put(name, summary);
            }
        });
        return result;
    }
}
