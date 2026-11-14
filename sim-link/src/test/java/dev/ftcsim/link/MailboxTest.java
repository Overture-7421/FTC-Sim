package dev.ftcsim.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.link.wire.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class MailboxTest {

    private static Mailbox.Item deliver(Event event) {
        return new Mailbox.Item.Deliver(event);
    }

    @Test
    void aNewerSnapshotReplacesAnOlderOneThatNobodyTookAndKeepsTheNewerPlace() throws InterruptedException {
        Mailbox mailbox = new Mailbox();
        mailbox.offer(new Event.Snapshot(Messages.snapshot(1)));
        mailbox.offer(new Event.Status(Messages.status(10)));
        mailbox.offer(new Event.Snapshot(Messages.snapshot(2)));
        mailbox.offer(new Event.Failed("a session failed"));
        mailbox.offer(new Event.Snapshot(Messages.snapshot(3)));

        assertEquals(deliver(new Event.Status(Messages.status(10))), mailbox.take());
        assertEquals(deliver(new Event.Failed("a session failed")), mailbox.take(), "events that must not be lost are never replaced");
        assertEquals(deliver(new Event.Snapshot(Messages.snapshot(3))), mailbox.take());
    }

    @Test
    void aSessionThatOpensMakesWhatWasWaitingFromTheSessionBeforeStale() throws InterruptedException {
        Mailbox mailbox = new Mailbox();
        mailbox.offer(new Event.Snapshot(Messages.snapshot(1)));
        mailbox.offer(new Event.Status(Messages.status(10)));
        mailbox.offer(new Event.Opened(Messages.info("second")));
        mailbox.offer(new Event.Snapshot(Messages.snapshot(2)));

        assertEquals(deliver(new Event.Opened(Messages.info("second"))), mailbox.take());
        assertEquals(deliver(new Event.Snapshot(Messages.snapshot(2))), mailbox.take());
    }

    @Test
    void aRefusalIsNeverReplacedOrDroppedAndKeepsItsPlaceAmongTheOthers() throws InterruptedException {
        Mailbox mailbox = new Mailbox();
        mailbox.offer(new Event.Refused("first"));
        mailbox.offer(new Event.Status(Messages.status(1)));
        mailbox.offer(new Event.Opened(Messages.info("a")));
        mailbox.offer(new Event.Refused("second"));

        assertEquals(deliver(new Event.Refused("first")), mailbox.take());
        assertEquals(deliver(new Event.Opened(Messages.info("a"))), mailbox.take(), "the status from before the session is stale");
        assertEquals(deliver(new Event.Refused("second")), mailbox.take());
    }

    @Test
    void aCatalogueIsNeverReplacedAndNeitherIsItDroppedByASessionThatOpens() throws InterruptedException {
        Mailbox mailbox = new Mailbox();
        mailbox.offer(new Event.Catalogue(Messages.catalogue("first")));
        mailbox.offer(new Event.Opened(Messages.info("a")));
        mailbox.offer(new Event.Catalogue(Messages.catalogue("second")));

        assertEquals(deliver(new Event.Catalogue(Messages.catalogue("first"))), mailbox.take());
        assertEquals(deliver(new Event.Opened(Messages.info("a"))), mailbox.take());
        assertEquals(deliver(new Event.Catalogue(Messages.catalogue("second"))), mailbox.take());
    }

    @Test
    void aListenerIsTakenOnAfterWhatWasOfferedBeforeItAndBeforeWhatIsOfferedAfterIt() throws InterruptedException {
        Mailbox mailbox = new Mailbox();
        SimulatorLink.Listener listener = new RecordingListener();
        mailbox.offer(new Event.Catalogue(Messages.catalogue("before")));
        mailbox.register(listener);
        mailbox.offer(new Event.Failed("after"));

        assertEquals(deliver(new Event.Catalogue(Messages.catalogue("before"))), mailbox.take());
        assertEquals(new Mailbox.Item.Register(listener), mailbox.take());
        assertEquals(deliver(new Event.Failed("after")), mailbox.take());
    }

    @Test
    void aFailureOfTheLinkIsTheLastThingDeliveredAndEverythingWaitingBeforeItIsDropped() throws InterruptedException {
        Mailbox mailbox = new Mailbox();
        mailbox.offer(new Event.Snapshot(Messages.snapshot(1)));

        mailbox.finishWith(new Event.Failed("gone"));
        mailbox.offer(new Event.Snapshot(Messages.snapshot(2)));
        mailbox.register(new RecordingListener());

        assertEquals(deliver(new Event.Failed("gone")), mailbox.take());
        assertNull(mailbox.take());
    }

    @Test
    void closingEndsTheDeliveryAtOnceAndDropsWhatWaits() throws InterruptedException {
        Mailbox mailbox = new Mailbox();
        mailbox.offer(new Event.Failed("never delivered"));

        mailbox.close();

        assertNull(mailbox.take());
    }
}
