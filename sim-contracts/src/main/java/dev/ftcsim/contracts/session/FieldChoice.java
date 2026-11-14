package dev.ftcsim.contracts.session;

import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.StartPose;
import java.util.List;

/**
 * A field a session can be opened on, as far as a person choosing needs to know before it is open.
 *
 * @param id            what {@link SessionRequest#field()} takes; {@code none} is the bare floor
 * @param displayName   what to call it in a list
 * @param startPoses    the places the field offers to start the robot, in the field's own order; empty on a bare floor
 * @param humanActions  what a person at the field can do while the session runs; empty on a bare floor
 */
public record FieldChoice(String id, String displayName, List<StartPose> startPoses, List<HumanAction> humanActions) {

    public FieldChoice {
        startPoses = List.copyOf(startPoses);
        humanActions = List.copyOf(humanActions);
    }
}
