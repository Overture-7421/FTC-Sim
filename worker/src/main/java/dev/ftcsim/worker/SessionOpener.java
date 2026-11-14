package dev.ftcsim.worker;

import dev.ftcsim.contracts.session.SessionRequest;
import java.io.IOException;

/** Opens the session a request asks for. */
interface SessionOpener {

    /**
     * Resolves the request and starts the simulation.
     *
     * @throws IOException if anything the request names is missing or unusable; the message is for the person who made it
     */
    RunningSession open(SessionRequest request) throws IOException;
}
