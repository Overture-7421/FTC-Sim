package dev.ftcsim.importer.resources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * What one Android library, or one module of the team's project, contributes to the app's resources: the names
 * of its resources by type, its styleables, and the default values of the kinds the simulator's Android layer can
 * answer (strings, integers, booleans and colors).
 */
final class ResourceLibrary {

    /** A value resource as written in {@code res/values}; {@code text} is still the raw text of the element. */
    record Value(String type, String name, String text) {}

    /** Where a library keeps its raw asset files. */
    interface Assets {
        Assets NONE = target -> {};

        /** Copies the library's assets below {@code target}, replacing files of the same name. */
        void copyInto(Path target) throws IOException;
    }

    final String packageName;
    /** Whether the library is part of the team's project: its R class then holds every resource of the app. */
    final boolean ofTheProject;
    /** Resource names by type (anim, id, string, ...); styleables are kept apart. */
    final Map<String, SortedSet<String>> symbols;
    /** The attributes of each styleable as ids of this library's own table, in the order the array lists them. */
    final Map<String, List<String>> styleableArrays;
    /** The index of each styleable attribute, by the name {@code Styleable_attribute}. */
    final Map<String, String> styleableIndexes;
    /** The name of each attribute by the id this library's own table gave it. */
    final Map<String, String> attributeNames;
    final List<Value> values;
    final Assets assets;

    ResourceLibrary(
            String packageName,
            boolean ofTheProject,
            Map<String, SortedSet<String>> symbols,
            Map<String, List<String>> styleableArrays,
            Map<String, String> styleableIndexes,
            Map<String, String> attributeNames,
            List<Value> values,
            Assets assets) {
        this.packageName = packageName;
        this.ofTheProject = ofTheProject;
        this.symbols = symbols;
        this.styleableArrays = styleableArrays;
        this.styleableIndexes = styleableIndexes;
        this.attributeNames = attributeNames;
        this.values = values;
        this.assets = assets;
    }
}
