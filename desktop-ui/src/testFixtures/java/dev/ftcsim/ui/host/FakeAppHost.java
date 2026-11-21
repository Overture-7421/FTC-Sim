package dev.ftcsim.ui.host;

import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.ui.link.FakeSimulatorLink;
import java.io.IOException;

/**
 * The host for developing the window on its own: a {@link FakeSimulatorLink} that takes a moment to open, a
 * {@link FakeProgramPreparer}. It runs no robot program, which is why the window
 * labels itself as a fake whenever it runs on this host ({@link #isFake()}).
 */
public final class FakeAppHost implements AppHost {
    /** How long the fake link takes to open a session, so that the window can be seen waiting for it. */
    public static final long OPEN_DELAY_MILLIS = 600L;

    /** Opens a link, or fails the way a worker that does not start would. */
    @FunctionalInterface
    public interface LinkOpener {
        SimulatorLink open() throws IOException;
    }

    private final ProgramPreparer preparer;
    private final LinkOpener links;

    /** A host in real time, for {@code :desktop-ui:runFake}. */
    public FakeAppHost() {
        this(FakeProgramPreparer.realTime(), () -> new FakeSimulatorLink(OPEN_DELAY_MILLIS, null));
    }

    public FakeAppHost(ProgramPreparer preparer, LinkOpener links) {
        this.preparer = preparer;
        this.links = links;
    }

    @Override
    public SimulatorLink openLink() throws IOException {
        return links.open();
    }

    @Override
    public ProgramPreparer preparer() {
        return preparer;
    }

    @Override
    public boolean isFake() {
        return true;
    }
}
