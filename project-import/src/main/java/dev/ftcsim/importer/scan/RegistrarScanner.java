package dev.ftcsim.importer.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Finds the methods that register OpModes in code ({@code @OpModeRegistrar}), such as the one a library uses to add
 * its own OpMode to the list. The OpModes they add exist only when the SDK runs the method, so no scan of class
 * files can list them; pointing at the methods says why the simulator may list more OpModes than a program's scan
 * found.
 */
final class RegistrarScanner {
    private static final String ANNOTATION = "Lcom/qualcomm/robotcore/eventloop/opmode/OpModeRegistrar;";
    /** A class that uses the annotation holds its name in the constant pool, so most classes are skipped by a byte search. */
    private static final byte[] MARK = ANNOTATION.substring(1, ANNOTATION.length() - 1).getBytes(StandardCharsets.US_ASCII);

    private RegistrarScanner() {}

    static List<Finding> scan(List<Path> roots) throws IOException {
        List<Finding> found = new ArrayList<>();
        for (Path root : roots) {
            ClassFiles.forEach(root, (internalName, bytes) -> {
                if (mentions(bytes)) {
                    new ClassReader(bytes).accept(
                            new Registrars(found), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            });
        }
        return found;
    }

    private static boolean mentions(byte[] bytes) {
        int last = bytes.length - MARK.length;
        for (int start = 0; start <= last; start++) {
            if (bytes[start] == MARK[0]) {
                int offset = 1;
                while (offset < MARK.length && bytes[start + offset] == MARK[offset]) {
                    offset++;
                }
                if (offset == MARK.length) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class Registrars extends ClassVisitor {
        private final List<Finding> found;
        private String className = "";

        Registrars(List<Finding> found) {
            super(Opcodes.ASM9);
            this.found = found;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name.replace('/', '.');
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (visible && annotation.equals(ANNOTATION)) {
                        found.add(new Finding(
                                Finding.Codes.OPMODE_REGISTRAR,
                                className,
                                name + descriptor,
                                "The SDK calls this method when the program starts, and it may register OpModes that no class file declares."
                                        + " The simulator lists those as the robot controller does, so its list can be longer than the one found in the class files."));
                    }
                    return null;
                }
            };
        }
    }
}
