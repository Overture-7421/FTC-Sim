package dev.ftcsim.importer.scan;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.importer.scan.ClassIndex.Inheritance;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Finds the OpModes a program declares from its class files, without loading a class. It applies the rules of the
 * FTC SDK's own annotation scan ({@code AnnotatedOpModeClassFilter}): a public class that extends {@code OpMode}
 * and carries {@code @TeleOp} or {@code @Autonomous} (not both, and not as a non-static inner class), whose name
 * is usable, and that is not {@code @Disabled}. The SDK host's list is the authority at run time; this one lets
 * a prepared program say what it holds before any simulation starts. OpModes that a program registers in code
 * ({@code @OpModeRegistrar}) are not found; {@link ProgramScan#opModeRegistrarsIn} points at the methods that add them.
 */
final class OpModeScanner {
    static final String OP_MODE = "com/qualcomm/robotcore/eventloop/opmode/OpMode";
    private static final String TELEOP = "Lcom/qualcomm/robotcore/eventloop/opmode/TeleOp;";
    private static final String AUTONOMOUS = "Lcom/qualcomm/robotcore/eventloop/opmode/Autonomous;";
    private static final String DISABLED = "Lcom/qualcomm/robotcore/eventloop/opmode/Disabled;";

    private OpModeScanner() {}

    static ProgramScan.OpModes scan(List<Path> roots, ClassIndex classes) throws IOException {
        List<OpModeDescriptor> found = new ArrayList<>();
        List<Finding> notes = new ArrayList<>();
        for (Path root : roots) {
            ClassFiles.forEach(root, (internalName, bytes) -> {
                Declaration declaration = new Declaration();
                new ClassReader(bytes).accept(declaration, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (declaration.teleOp || declaration.autonomous) {
                    consider(declaration, classes, found, notes);
                }
            });
        }
        return new ProgramScan.OpModes(nameDuplicates(found, notes), notes);
    }

    private static void consider(Declaration declaration, ClassIndex classes, List<OpModeDescriptor> found, List<Finding> notes) {
        String className = declaration.name.replace('/', '.');
        String reason = null;
        if (declaration.teleOp && declaration.autonomous) {
            reason = "it is annotated as both @TeleOp and @Autonomous";
        } else if (classes.inheritsFrom(declaration.name, OP_MODE) == Inheritance.NO) {
            reason = "it does not extend OpMode";
        } else if (classes.inheritsFrom(declaration.name, OP_MODE) == Inheritance.UNKNOWN) {
            reason = "its superclass chain leaves the class path, so it cannot be an OpMode that loads";
        } else if (declaration.isNonStaticInner()) {
            reason = "it is an inner class that is not static";
        } else if (!declaration.isPublic()) {
            reason = "it is not public";
        }
        String opModeName = declaration.opModeName();
        if (reason == null && (opModeName.isBlank() || (opModeName.startsWith("$") && opModeName.endsWith("$")))) {
            reason = "\"" + opModeName + "\" is not a legal OpMode name";
        }
        if (reason != null) {
            notes.add(new Finding(
                    Finding.Codes.OPMODE_REJECTED, className, "", "The SDK will not register this annotated class because " + reason + "."));
        } else if (!declaration.disabled) {
            found.add(new OpModeDescriptor(
                    opModeName, declaration.group, declaration.autonomous ? OpModeKind.AUTONOMOUS : OpModeKind.TELEOP, className));
        }
    }

    /** Like the SDK, OpModes that share a name are told apart by appending the class name; the result is sorted as the SDK host lists them. */
    private static List<OpModeDescriptor> nameDuplicates(List<OpModeDescriptor> found, List<Finding> notes) {
        Map<String, Integer> counts = new HashMap<>();
        found.forEach(opMode -> counts.merge(opMode.name(), 1, Integer::sum));
        List<OpModeDescriptor> named = new ArrayList<>();
        for (OpModeDescriptor opMode : found) {
            if (counts.get(opMode.name()) > 1) {
                String simple = opMode.className().substring(opMode.className().lastIndexOf('.') + 1);
                named.add(new OpModeDescriptor(opMode.name() + "-" + simple.substring(simple.lastIndexOf('$') + 1), opMode.group(), opMode.kind(), opMode.className()));
                notes.add(new Finding(
                        Finding.Codes.OPMODE_DUPLICATE_NAME, opMode.className(), "", "Another OpMode is also named \"" + opMode.name() + "\"."));
            } else {
                named.add(opMode);
            }
        }
        named.sort(Comparator.comparing(OpModeDescriptor::group).thenComparing(OpModeDescriptor::name));
        return List.copyOf(named);
    }

    /** What a class file says about being an OpMode. */
    private static final class Declaration extends ClassVisitor {
        String name = "";
        int access;
        boolean teleOp;
        boolean autonomous;
        boolean disabled;
        String annotatedName = "";
        String group = "";
        boolean enclosed;
        int innerAccess;
        String innerName;

        Declaration() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            this.access = access;
        }

        @Override
        public void visitOuterClass(String owner, String methodName, String descriptor) {
            enclosed = true;
        }

        @Override
        public void visitInnerClass(String innerClass, String outerName, String simpleName, int innerAccess) {
            if (innerClass.equals(name)) {
                enclosed = true;
                this.innerAccess = innerAccess;
                this.innerName = simpleName;
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!visible) {
                return null;
            }
            switch (descriptor) {
                case DISABLED -> disabled = true;
                case TELEOP -> teleOp = true;
                case AUTONOMOUS -> autonomous = true;
                default -> {
                    return null;
                }
            }
            if (descriptor.equals(DISABLED)) {
                return null;
            }
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String attribute, Object value) {
                    if (attribute.equals("name")) {
                        annotatedName = (String) value;
                    } else if (attribute.equals("group")) {
                        group = (String) value;
                    }
                }
            };
        }

        boolean isNonStaticInner() {
            return enclosed && (innerAccess & Opcodes.ACC_STATIC) == 0;
        }

        /** {@code Class.getModifiers()}: a nested class has the flags of its InnerClasses entry. */
        boolean isPublic() {
            return ((innerName != null ? innerAccess : access) & Opcodes.ACC_PUBLIC) != 0;
        }

        /** The name of the annotation, or the simple name of the class when it gives none. */
        String opModeName() {
            if (!annotatedName.isBlank()) {
                return annotatedName;
            }
            if (innerName != null) {
                return innerName;
            }
            return name.substring(name.lastIndexOf('/') + 1);
        }
    }
}
