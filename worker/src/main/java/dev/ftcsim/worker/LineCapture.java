package dev.ftcsim.worker;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Turns what is written to a stream into log lines, `tag: text`, one for each line of text. */
final class LineCapture extends OutputStream {

    private final Consumer<String> log;
    private final String tag;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private LineCapture(Consumer<String> log, String tag) {
        this.log = log;
        this.tag = tag;
    }

    /** A stream whose lines go to [log] with [tag] in front, which is how logcat shows what a program prints. */
    static PrintStream stream(Consumer<String> log, String tag) {
        return new PrintStream(new LineCapture(log, tag), true, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void write(int value) {
        if (value == '\n') {
            emit();
        } else {
            pending.write(value);
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            write(bytes[i]);
        }
    }

    private void emit() {
        String text = pending.toString(StandardCharsets.UTF_8);
        pending.reset();
        log.accept(tag + ": " + (text.endsWith("\r") ? text.substring(0, text.length() - 1) : text));
    }
}
