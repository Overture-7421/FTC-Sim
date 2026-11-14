package dev.ftcsim.link.wire;

/**
 * Turns the commands and events of the port into frame payloads and back. A payload is one kind byte followed by the
 * fields of that kind; see the package description for the layout. Decoding is strict: an unknown kind, a field out
 * of bounds, a payload that ends early or has bytes left over is a {@link WireException}, never skipped.
 */
public final class WireCodec {

    private WireCodec() {}

    /** @throws IllegalArgumentException if the command cannot be represented, for example a list beyond the bound */
    public static byte[] encode(Command command) {
        WireWriter w = new WireWriter(64);
        switch (command) {
            case Command.Open open -> {
                w.unsignedByte(Protocol.OPEN);
                ContractCodec.writeRequest(w, open.request());
            }
            case Command.Init init -> {
                w.unsignedByte(Protocol.INIT);
                w.string(init.opMode());
            }
            case Command.Start _ -> w.unsignedByte(Protocol.START);
            case Command.Stop _ -> w.unsignedByte(Protocol.STOP);
            case Command.SetGamepad pad -> {
                w.unsignedByte(Protocol.SET_GAMEPAD);
                w.unsignedByte(pad.gamepad());
                ContractCodec.writeGamepad(w, pad.state());
            }
            case Command.PerformHumanAction action -> {
                w.unsignedByte(Protocol.HUMAN_ACTION);
                w.string(action.actionId());
            }
            case Command.SetPaused paused -> {
                w.unsignedByte(Protocol.SET_PAUSED);
                w.bool(paused.paused());
            }
            case Command.SetSpeed speed -> {
                w.unsignedByte(Protocol.SET_SPEED);
                w.doubleBits(speed.speed());
            }
            case Command.Reset _ -> w.unsignedByte(Protocol.RESET);
            case Command.Close _ -> w.unsignedByte(Protocol.CLOSE);
            case Command.ArmMatch arm -> {
                w.unsignedByte(Protocol.ARM_MATCH);
                w.string(arm.autoOpMode());
                w.string(arm.teleOpMode());
            }
            case Command.StartMatch _ -> w.unsignedByte(Protocol.START_MATCH);
        }
        return w.finish();
    }

    /** @throws IllegalArgumentException if the event cannot be represented, for example a list beyond the bound */
    public static byte[] encode(Event event) {
        WireWriter w = new WireWriter(event instanceof Event.Snapshot snapshot ? 32 + 36 * snapshot.snapshot().bodies().size() : 256);
        switch (event) {
            case Event.Opened opened -> {
                w.unsignedByte(Protocol.OPENED);
                ContractCodec.writeInfo(w, opened.info());
            }
            case Event.Snapshot snapshot -> {
                w.unsignedByte(Protocol.SNAPSHOT);
                ContractCodec.writeSnapshot(w, snapshot.snapshot());
            }
            case Event.Status status -> {
                w.unsignedByte(Protocol.STATUS);
                ContractCodec.writeStatus(w, status.status());
            }
            case Event.Failed failed -> {
                w.unsignedByte(Protocol.FAILED);
                w.string(failed.message());
            }
            case Event.Catalogue catalogue -> {
                w.unsignedByte(Protocol.CATALOGUE);
                ContractCodec.writeCatalogue(w, catalogue.catalogue());
            }
            case Event.Refused refused -> {
                w.unsignedByte(Protocol.COMMAND_REFUSED);
                w.string(refused.message());
            }
        }
        return w.finish();
    }

    public static Command decodeCommand(byte[] payload) throws WireException {
        WireReader r = new WireReader(payload);
        int kind = r.unsignedByte();
        try {
            Command command =
                    switch (kind) {
                        case Protocol.OPEN -> new Command.Open(ContractCodec.readRequest(r));
                        case Protocol.INIT -> new Command.Init(r.string("the OpMode name"));
                        case Protocol.START -> new Command.Start();
                        case Protocol.STOP -> new Command.Stop();
                        case Protocol.SET_GAMEPAD -> new Command.SetGamepad(r.unsignedByte(), ContractCodec.readGamepad(r));
                        case Protocol.HUMAN_ACTION -> new Command.PerformHumanAction(r.string("the human action id"));
                        case Protocol.SET_PAUSED -> new Command.SetPaused(r.bool());
                        case Protocol.SET_SPEED -> new Command.SetSpeed(r.doubleBits());
                        case Protocol.RESET -> new Command.Reset();
                        case Protocol.CLOSE -> new Command.Close();
                        case Protocol.ARM_MATCH ->
                                new Command.ArmMatch(r.string("the autonomous OpMode name"), r.string("the driver-controlled OpMode name"));
                        case Protocol.START_MATCH -> new Command.StartMatch();
                        default -> throw new WireException("protocol version " + Protocol.VERSION + " has no command of that kind");
                    };
            r.end();
            return command;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new WireException("a command of kind 0x" + Integer.toHexString(kind) + " is invalid: " + invalid.getMessage(), invalid);
        } catch (WireException bad) {
            throw new WireException("a command of kind 0x" + Integer.toHexString(kind) + " is malformed: " + bad.getMessage(), bad);
        }
    }

    public static Event decodeEvent(byte[] payload) throws WireException {
        WireReader r = new WireReader(payload);
        int kind = r.unsignedByte();
        try {
            Event event =
                    switch (kind) {
                        case Protocol.OPENED -> new Event.Opened(ContractCodec.readInfo(r));
                        case Protocol.SNAPSHOT -> new Event.Snapshot(ContractCodec.readSnapshot(r));
                        case Protocol.STATUS -> new Event.Status(ContractCodec.readStatus(r));
                        case Protocol.FAILED -> new Event.Failed(r.string("the failure message"));
                        case Protocol.CATALOGUE -> new Event.Catalogue(ContractCodec.readCatalogue(r));
                        case Protocol.COMMAND_REFUSED -> new Event.Refused(r.string("the refusal message"));
                        default -> throw new WireException("protocol version " + Protocol.VERSION + " has no event of that kind");
                    };
            r.end();
            return event;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new WireException("an event of kind 0x" + Integer.toHexString(kind) + " is invalid: " + invalid.getMessage(), invalid);
        } catch (WireException bad) {
            throw new WireException("an event of kind 0x" + Integer.toHexString(kind) + " is malformed: " + bad.getMessage(), bad);
        }
    }
}
