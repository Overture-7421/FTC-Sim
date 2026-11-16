package dev.ftcsim.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class WorkerLogTest {

    @Test
    void whatTeamCodePrintsBecomesOneLogLineForEachLineOfText() {
        List<String> lines = new ArrayList<>();
        PrintStream stream = LineCapture.stream(lines::add, "I/System.out");

        stream.print("half a");
        stream.println(" line");
        stream.print("two\r\nlines\n");
        stream.print("unfinished");

        assertEquals(List.of("I/System.out: half a line", "I/System.out: two", "I/System.out: lines"), lines);
    }

    @Test
    void everyLineReachesTheStreamAndTheSinkInOrderAndNothingIsLostWhileTheyAreDrained() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        List<String> sunk = new CopyOnWriteArrayList<>();
        try (WorkerLog log = new WorkerLog(new PrintStream(written, true, StandardCharsets.UTF_8))) {
            log.sink(sunk::addAll);
            for (int i = 0; i < 100; i++) {
                log.accept("D/Test: line " + i);
            }
        }

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            expected.add("D/Test: line " + i);
        }
        assertEquals(expected, sunk, "the sink hears every line in the order written");
        assertEquals(expected, written.toString(StandardCharsets.UTF_8).lines().toList(), "and so does the stream");
    }

    @Test
    void aSinkThatThrowsDoesNotStopTheStream() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        try (WorkerLog log = new WorkerLog(new PrintStream(written, true, StandardCharsets.UTF_8))) {
            log.sink(lines -> {
                throw new IllegalStateException("the console is gone");
            });
            log.accept("D/Test: still written");
        }

        assertTrue(written.toString(StandardCharsets.UTF_8).contains("D/Test: still written"));
    }
}
