package dev.ftcsim.contracts.session;

import java.util.List;

/**
 * What can be asked for before a session is open: the fields, with the start poses each one offers, and the motors a
 * robot mapping may name.
 *
 * @param fields    the fields a {@link SessionRequest} may name, in the order to list them
 * @param motors    the motors a {@link RobotMapping} may name, the default first
 */
public record Catalogue(List<FieldChoice> fields, List<MotorChoice> motors) {

    public Catalogue {
        fields = List.copyOf(fields);
        motors = List.copyOf(motors);
    }

    /** A catalogue that offers no motors. */
    public Catalogue(List<FieldChoice> fields) {
        this(fields, List.of());
    }
}