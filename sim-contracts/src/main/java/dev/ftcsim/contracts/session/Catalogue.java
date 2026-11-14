package dev.ftcsim.contracts.session;

import java.util.List;

/**
 * What can be asked for before a session is open: the fields, with the start poses each one offers, and the robot
 * profiles that ship with the simulator.
 *
 * @param fields    the fields a {@link SessionRequest} may name, in the order to list them
 * @param profiles  the names of the bundled robot profiles, which {@link SessionRequest#profile()} takes
 */
public record Catalogue(List<FieldChoice> fields, List<String> profiles) {

    public Catalogue {
        fields = List.copyOf(fields);
        profiles = List.copyOf(profiles);
    }
}
