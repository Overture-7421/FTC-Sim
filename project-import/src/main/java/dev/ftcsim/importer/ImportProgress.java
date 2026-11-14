package dev.ftcsim.importer;

/**
 * What an import reports while it runs and how it is told to stop. Methods are called on the importing thread, so
 * a user interface has to hand the work over to its own thread; {@link #isCancelled()} may be set from any thread.
 */
public interface ImportProgress {
    /** An import that reports nothing and is never cancelled. */
    ImportProgress SILENT = new ImportProgress() {
        @Override
        public void stage(ImportStage stage) {}

        @Override
        public void message(String text) {}

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    /** A new step starts. */
    void stage(ImportStage stage);

    /** A line of detail: a file being copied, a line of the project's Gradle output, a finding. */
    void message(String text);

    /** Whether the user asked to stop. The importer checks often and stops at the next safe point. */
    boolean isCancelled();

    /** Stops the import with {@link ImportException.Failure#CANCELLED} when it was cancelled. */
    default void checkCancelled() {
        if (isCancelled()) {
            throw new ImportException(ImportException.Failure.CANCELLED, "The import was cancelled.");
        }
    }
}
