package dev.ftcsim.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.link.wire.Command;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class OutboxTest {
    private static Command.SetGamepad pad(int gamepad, float x) {
        return new Command.SetGamepad(gamepad, GamepadState.sticks(x, 0f, 0f, 0f));
    }

    @Test
    void commandsComeOutInTheOrderTheyWentIn() throws InterruptedException {
        Outbox outbox = new Outbox();
        outbox.add(new Command.Init("A"));
        outbox.add(new Command.Start());
        outbox.add(new Command.SetPaused(true));
        outbox.add(new Command.Stop());

        assertEquals(new Command.Init("A"), outbox.take());
        assertEquals(new Command.Start(), outbox.take());
        assertEquals(new Command.SetPaused(true), outbox.take());
        assertEquals(new Command.Stop(), outbox.take());
    }

    @Test
    void ofEachGamepadOnlyTheLatestStateWaitsAndItTakesThePlaceOfTheCallThatSetIt() throws InterruptedException {
        Outbox outbox = new Outbox();
        outbox.setGamepad(pad(1, 0.1f));
        outbox.setGamepad(pad(2, 0.2f));
        outbox.add(new Command.Init("A"));
        outbox.setGamepad(pad(1, 0.3f));
        outbox.setGamepad(pad(1, 0.4f));
        outbox.add(new Command.Start());
        outbox.setGamepad(pad(2, 0.5f));

        assertEquals(new Command.Init("A"), outbox.take(), "the commands keep their order among themselves");
        assertEquals(pad(1, 0.4f), outbox.take(), "gamepad 1 jumped to its latest state, which was set after Init");
        assertEquals(new Command.Start(), outbox.take());
        assertEquals(pad(2, 0.5f), outbox.take(), "gamepad 2 never sends the 0.2 that nobody was waiting for");
        outbox.abandon();
        assertNull(outbox.take());
    }

    @Test
    void aGamepadStateIsNeverSentAheadOfACommandThatWasGivenBeforeItWasSet() throws InterruptedException {
        Outbox outbox = new Outbox();
        outbox.add(new Command.Init("A"));
        outbox.setGamepad(pad(1, 0.9f));

        assertEquals(new Command.Init("A"), outbox.take());
        assertEquals(pad(1, 0.9f), outbox.take());
    }

    @Test
    void finishingDropsWhatWaitsSendsTheLastCommandAndThenEnds() throws InterruptedException {
        Outbox outbox = new Outbox();
        outbox.add(new Command.Init("A"));
        outbox.setGamepad(pad(1, 0.1f));

        outbox.finishWith(new Command.Close());
        outbox.add(new Command.Start());
        outbox.setGamepad(pad(2, 0.2f));

        assertEquals(new Command.Close(), outbox.take());
        assertNull(outbox.take(), "nothing is accepted after the end");
        assertNull(outbox.take());
    }

    @Test
    void takingWaitsUntilSomethingIsGiven() throws Exception {
        Outbox outbox = new Outbox();
        AtomicReference<Command> taken = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                taken.set(outbox.take());
            } catch (InterruptedException interrupted) {
                taken.set(new Command.Close());
            }
            done.countDown();
        });
        writer.start();

        assertTrue(!done.await(200, TimeUnit.MILLISECONDS), "the writer has nothing to write");
        outbox.add(new Command.Reset());

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(new Command.Reset(), taken.get());
    }
}
