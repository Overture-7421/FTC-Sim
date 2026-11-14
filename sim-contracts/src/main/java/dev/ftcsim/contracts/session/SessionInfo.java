package dev.ftcsim.contracts.session;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import java.util.List;

/**
 * Everything a viewer needs once a session is open and before anything has happened.
 *
 * @param field   how to draw the field, or null on a bare floor
 * @param opModes the OpModes the robot program declares
 */
public record SessionInfo(FieldDescription field, List<OpModeDescriptor> opModes, RobotInfo robot) {

    public SessionInfo {
        opModes = List.copyOf(opModes);
    }
}
