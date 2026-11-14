package dev.ftcsim.hardware.lynx;

/** What a command handler answers with. A refusal is raised as {@link CommandRejected} instead. */
sealed interface Reply {

    /** Acknowledges a command that has no response of its own. */
    record Ack() implements Reply {}

    record Nack(int reason) implements Reply {}

    /** The payload of the command's response packet. */
    record Response(byte[] payload) implements Reply {}

    Reply ACK = new Ack();

    static Reply response(PayloadWriter payload) {
        return new Response(payload.toBytes());
    }
}
