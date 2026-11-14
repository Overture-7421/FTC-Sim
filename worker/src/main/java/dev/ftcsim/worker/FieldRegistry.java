package dev.ftcsim.worker;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.field.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** The fields a request can name, by id, and how to make each; {@code none} is a bare floor and always there. */
public final class FieldRegistry {

    /** The id of a session without a field. */
    public static final String NONE = "none";

    private final Map<String, Supplier<Field>> factories = new LinkedHashMap<>();

    /** Makes {@code id} mean the fields that {@code factory} makes. */
    public FieldRegistry register(String id, Supplier<Field> factory) {
        if (id.equals(NONE) || factories.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("the field id '" + id + "' is already taken");
        }
        return this;
    }

    /** The ids a request may name. */
    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        ids.add(NONE);
        ids.addAll(factories.keySet());
        return ids;
    }

    /** What a person choosing needs to know about each field, in the order of {@link #ids}; the bare floor offers nothing. */
    public List<FieldChoice> choices() {
        List<FieldChoice> choices = new ArrayList<>();
        choices.add(new FieldChoice(NONE, "Bare floor", List.of(), List.of()));
        factories.forEach((id, factory) -> {
            FieldDescription description = factory.get().description();
            choices.add(new FieldChoice(id, description.displayName(), description.startPoses(), description.humanActions()));
        });
        return choices;
    }

    /**
     * A new field for the id.
     *
     * @return empty for {@code none}
     * @throws IllegalArgumentException for an id that is not registered, with the ids that are
     */
    public Optional<Field> create(String id) {
        if (id.equals(NONE)) {
            return Optional.empty();
        }
        Supplier<Field> factory = factories.get(id);
        if (factory == null) {
            throw new IllegalArgumentException("there is no field '" + id + "'; the fields are " + String.join(", ", ids()));
        }
        return Optional.of(factory.get());
    }
}
