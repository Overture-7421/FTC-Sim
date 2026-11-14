package dev.ftcsim.contracts.field;

import java.util.List;

/**
 * What every body of one kind looks like, so that a snapshot only needs a kind and a pose for each body. A kind is
 * a game piece, a moving structure of the field or the robot chassis; the field package numbers them.
 */
public record BodyKind(int id, String name, List<FieldPart> parts) {

    public BodyKind {
        parts = List.copyOf(parts);
    }
}
