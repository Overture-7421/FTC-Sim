package dev.ftcsim.field.support;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * Watches which bodies are inside a region from one physics step to the next, so that a rule can act once when a
 * piece enters or leaves instead of on every step it spends there. Ids are always reported in ascending order.
 */
public final class Occupancy {

    /** What changed since the previous update. */
    public record Change(List<Integer> entered, List<Integer> left) {
        public Change {
            entered = List.copyOf(entered);
            left = List.copyOf(left);
        }
    }

    private final Region region;
    private final IntPredicate kinds;
    private Set<Integer> inside = new TreeSet<>();

    /** Watches the bodies of any kind. */
    public Occupancy(Region region) {
        this(region, kind -> true);
    }

    /** Watches only the bodies whose kind is accepted. */
    public Occupancy(Region region, IntPredicate kinds) {
        this.region = region;
        this.kinds = kinds;
    }

    public Change update(WorldSnapshot snapshot) {
        Set<Integer> now = new TreeSet<>();
        List<Integer> entered = new ArrayList<>();
        for (BodyState body : snapshot.bodies()) {
            if (kinds.test(body.kind()) && region.contains(body.x(), body.y(), body.z())) {
                now.add(body.entityId());
                if (!inside.contains(body.entityId())) {
                    entered.add(body.entityId());
                }
            }
        }
        List<Integer> left = new ArrayList<>();
        for (int id : inside) {
            if (!now.contains(id)) {
                left.add(id);
            }
        }
        inside = now;
        return new Change(entered, left);
    }

    /** The ids that were inside at the last update, ascending. */
    public Set<Integer> inside() {
        return Set.copyOf(inside);
    }
}
