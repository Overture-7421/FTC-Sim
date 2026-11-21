package dev.ftcsim.contracts.session;

import java.util.List;

/**
 * What can be asked for before a session is open: the fields, with the start poses each one offers.
 *
 * @param fields    the fields a {@link SessionRequest} may name, in the order to list them
 */
public record Catalogue(List<FieldChoice> fields) {

    public Catalogue {
        fields = List.copyOf(fields);
    }
}