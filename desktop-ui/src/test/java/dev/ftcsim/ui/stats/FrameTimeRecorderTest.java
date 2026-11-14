package dev.ftcsim.ui.stats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameTimeRecorderTest {
    @Test
    void anUnfilledRecorderReturnsWhatWasRecordedInOrder() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(4);

        recorder.record(10);
        recorder.record(20);

        assertArrayEquals(new long[] {10, 20}, recorder.copyIntervals());
    }

    @Test
    void aFullRecorderKeepsTheNewestIntervalsOldestFirst() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(3);

        for (long value = 1; value <= 7; value++) {
            recorder.record(value);
        }

        assertArrayEquals(new long[] {5, 6, 7}, recorder.copyIntervals());
    }

    @Test
    void theFirstMarkOnlyStartsTheClockAndABrokenSequenceDoesNotRecordTheRest() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(8);

        recorder.markFrame(1_000);
        recorder.markFrame(1_016);
        recorder.breakSequence();
        recorder.markFrame(5_000_000);
        recorder.markFrame(5_000_016);

        assertArrayEquals(new long[] {16, 16}, recorder.copyIntervals(), "the long gap in between is not an interval");
    }

    @Test
    void clearingHidesWhatWasThereButNotWhatComesAfter() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(4);
        recorder.record(1);
        recorder.record(2);

        recorder.clear();
        assertEquals(0, recorder.copyIntervals().length);
        recorder.record(3);

        assertArrayEquals(new long[] {3}, recorder.copyIntervals());
    }

    @Test
    void clearingAFullRingHidesEverythingToo() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(2);
        for (long value = 1; value <= 5; value++) {
            recorder.record(value);
        }

        recorder.clear();
        recorder.record(6);

        assertArrayEquals(new long[] {6}, recorder.copyIntervals());
    }

    @Test
    void aRecorderWithoutRoomIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new FrameTimeRecorder(0));
    }

    @Test
    void startingToRecordForgetsWhatAnEarlierRecordingLeft() {
        StatSet stats = new StatSet();
        FrameTimeRecorder recorder = stats.recorder("frames", 8);
        stats.startRecording();
        recorder.record(1_000_000);
        assertEquals(1, stats.summaries().get("frames").count());

        stats.stopRecording();
        stats.startRecording();

        assertTrue(stats.summaries().isEmpty(), "the second recording starts empty");
        assertTrue(stats.recording());
    }
}
