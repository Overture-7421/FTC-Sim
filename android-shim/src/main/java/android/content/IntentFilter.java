package android.content;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The actions and categories an intent receiver is interested in. */
public class IntentFilter {
    private final Set<String> actions = new LinkedHashSet<>();
    private final Set<String> categories = new LinkedHashSet<>();

    public IntentFilter() {}

    public IntentFilter(String action) {
        addAction(action);
    }

    public IntentFilter(IntentFilter other) {
        actions.addAll(other.actions);
        categories.addAll(other.categories);
    }

    public final void addAction(String action) {
        actions.add(action);
    }

    public final int countActions() {
        return actions.size();
    }

    public final String getAction(int index) {
        return new ArrayList<>(actions).get(index);
    }

    public final boolean hasAction(String action) {
        return action != null && actions.contains(action);
    }

    public final boolean matchAction(String action) {
        return hasAction(action);
    }

    public final Iterator<String> actionsIterator() {
        return List.copyOf(actions).iterator();
    }

    public final void addCategory(String category) {
        categories.add(category);
    }

    public final int countCategories() {
        return categories.size();
    }

    public final boolean hasCategory(String category) {
        return categories.contains(category);
    }
}
