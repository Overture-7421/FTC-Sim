package dev.ftcsim.importer.pack;

/** The names a prepared program uses for its folders and files; {@link PreparedProgram} documents the layout. */
public final class PackageLayout {
    public static final String MANIFEST = "manifest.json";
    public static final String PROGRAM = "program";
    public static final String LIBRARIES = "libs";
    public static final String RESOURCES = "resources";
    /** The jar of Java resources, a file of the {@link #RESOURCES} folder. */
    public static final String JAVA_RESOURCES = "java-res.jar";

    private PackageLayout() {}
}
