package dev.ftcsim.importer;

/** The steps of an import, in the order they run; a user interface can show them as a progress list. */
public enum ImportStage {
    SNAPSHOT("Copying the project"),
    GRADLE_EXPORT("Building the project with its own Gradle build"),
    RESOURCES("Generating Android resources"),
    PACKAGE("Assembling the practice package"),
    ANALYSIS("Finding OpModes and checking compatibility"),
    VERIFY("Verifying the package and the original project");

    private final String label;

    ImportStage(String label) {
        this.label = label;
    }

    /** A short sentence for people. */
    public String label() {
        return label;
    }
}
