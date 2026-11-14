package dev.ftcsim.importer.scan;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Static analysis of a robot program's compiled classes: which OpModes it declares and what in its code may not
 * work in the simulator. Class files are read with ASM and no class is ever loaded or run, so scanning a program
 * costs little and cannot execute anything of it.
 *
 * <p>A scan is opened on the whole class path (the program first, then its libraries, in class path order),
 * which is what lets it follow superclass chains out of the program into the libraries.
 */
public final class ProgramScan implements AutoCloseable {

    /**
     * @param found the OpModes the SDK would register, ordered by group and name as the SDK host lists them
     * @param notes annotated classes the SDK would reject, and OpModes whose names had to be told apart
     */
    public record OpModes(List<OpModeDescriptor> found, List<Finding> notes) {
        public OpModes {
            found = List.copyOf(found);
            notes = List.copyOf(notes);
        }
    }

    private final ClassIndex classes;

    private ProgramScan(ClassIndex classes) {
        this.classes = classes;
    }

    public static ProgramScan open(List<Path> classPath) throws IOException {
        return new ProgramScan(ClassIndex.of(classPath));
    }

    /** The OpModes declared by the classes in {@code roots}, which are directories or jars of the class path. */
    public OpModes opModesIn(List<Path> roots) throws IOException {
        return OpModeScanner.scan(roots, classes);
    }

    /**
     * The methods in {@code roots} that register OpModes in code ({@code @OpModeRegistrar}), as findings. The
     * OpModes they register are not among those of {@link #opModesIn}, because they exist only when the method runs.
     */
    public List<Finding> opModeRegistrarsIn(List<Path> roots) throws IOException {
        return RegistrarScanner.scan(roots);
    }

    /**
     * Pointers to code in {@code roots} that may not work in the simulator: use of Android classes and members
     * that the simulator does not provide, loading of native libraries, threads and network use.
     *
     * @param android what the simulator's Android layer provides, or null when it is not known, in which case
     *                the Android checks are skipped and a finding says so
     */
    public List<Finding> compatibilityOf(List<Path> roots, AndroidApi android) throws IOException {
        return new PreflightScanner(classes, android).scan(roots);
    }

    @Override
    public void close() throws IOException {
        classes.close();
    }
}
