package dev.ftcsim.importer.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Cheap static pre-flight over the team's compiled code. Each finding names the class and method where something
 * was seen; none of them says the program fails, because whether a call runs depends on the program. What it
 * looks for: Android classes and members that the simulator's Android layer does not have, classes that no part of
 * the program's class path provides, calls that load native libraries, and the creation of threads, executors and
 * network connections.
 */
final class PreflightScanner {
    private static final int LIMIT = 5000;
    private static final Set<String> THREAD_TYPES = Set.of(
            "java/lang/Thread",
            "java/util/Timer",
            "java/util/concurrent/ThreadPoolExecutor",
            "java/util/concurrent/ScheduledThreadPoolExecutor",
            "java/util/concurrent/ForkJoinPool");
    private static final Set<String> NETWORK_TYPES = Set.of(
            "java/net/Socket",
            "java/net/ServerSocket",
            "java/net/DatagramSocket",
            "java/net/MulticastSocket",
            "java/net/http/HttpClient");
    private static final Set<String> EXECUTORS = Set.of(
            "java/util/concurrent/Executor", "java/util/concurrent/ExecutorService", "java/util/concurrent/ScheduledExecutorService");
    private static final Set<String> SUBMITTING =
            Set.of("execute", "submit", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay", "invokeAll", "invokeAny");

    private enum Verdict {
        PROVIDED,
        ANDROID_MISSING,
        NOT_PROVIDED,
        UNKNOWN
    }

    private final ClassIndex classes;
    private final AndroidApi android;
    private final Map<String, Verdict> verdicts = new HashMap<>();
    private final Set<Finding> findings = new LinkedHashSet<>();

    /**
     * @param classes the whole class path of the program
     * @param android what the simulator provides, or null when that is not known
     */
    PreflightScanner(ClassIndex classes, AndroidApi android) {
        this.classes = classes;
        this.android = android;
    }

    List<Finding> scan(List<Path> roots) throws IOException {
        if (android == null) {
            findings.add(Finding.general(
                    Finding.Codes.ANDROID_CHECK_SKIPPED,
                    "No simulator class path was given, so Android classes were not checked against what the simulator provides."));
        }
        for (Path root : roots) {
            ClassFiles.forEach(root, (internalName, bytes) ->
                    new ClassReader(bytes).accept(new ClassScan(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
        }
        List<Finding> all = List.copyOf(findings);
        if (all.size() <= LIMIT) {
            return all;
        }
        List<Finding> kept = new ArrayList<>(all.subList(0, LIMIT));
        kept.add(Finding.general(Finding.Codes.TRUNCATED, (all.size() - LIMIT) + " more findings are not listed."));
        return kept;
    }

    private void add(String code, String className, String member, String detail) {
        findings.add(new Finding(code, className.replace('/', '.'), member, detail));
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    private Verdict judge(String internalName) {
        if (classes.contains(internalName) || ClassLoader.getPlatformClassLoader().getResource(internalName + ".class") != null) {
            return Verdict.PROVIDED;
        }
        if (android == null) {
            return Verdict.UNKNOWN;
        }
        if (android.hasClass(internalName)) {
            return Verdict.PROVIDED;
        }
        boolean fromAndroid = internalName.startsWith("android/") || internalName.startsWith("dalvik/");
        return fromAndroid ? Verdict.ANDROID_MISSING : Verdict.NOT_PROVIDED;
    }

    /** Whether the class is one that the simulator's Android layer provides rather than the program's class path. */
    private boolean isSimulatorClass(String owner) {
        return android != null && !owner.startsWith("[") && !classes.contains(owner) && android.hasClass(owner);
    }

    private void useDescriptor(String className, String member, String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            for (Type argument : type.getArgumentTypes()) {
                useType(className, member, argument);
            }
            useType(className, member, type.getReturnType());
        } else {
            useType(className, member, type);
        }
    }

    private void useType(String className, String member, Type type) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() == Type.OBJECT) {
            useClass(className, member, element.getInternalName());
        }
    }

    /** @param nameOrDescriptor an internal name, or an array descriptor as the owner of an array's {@code clone()} is */
    private void useClass(String className, String member, String nameOrDescriptor) {
        String name = nameOrDescriptor;
        if (name.startsWith("[")) {
            Type element = Type.getType(name).getElementType();
            if (element.getSort() != Type.OBJECT) {
                return;
            }
            name = element.getInternalName();
        }
        if (name.equals(className)) {
            return;
        }
        switch (verdicts.computeIfAbsent(name, this::judge)) {
            case ANDROID_MISSING -> add(Finding.Codes.ANDROID_CLASS_MISSING, className, member,
                    "Uses " + dotted(name) + ", which the simulator's Android layer does not provide.");
            case NOT_PROVIDED -> add(Finding.Codes.CLASS_NOT_PROVIDED, className, member,
                    "Uses " + dotted(name) + ", which is on none of the program's class paths.");
            default -> {
            }
        }
    }

    private final class ClassScan extends ClassVisitor {
        private String className = "";

        ClassScan() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
            if ("java/lang/Thread".equals(superName)) {
                add(Finding.Codes.THREAD_USE, name, "", "The class extends java.lang.Thread.");
            }
            if (superName != null) {
                useClass(name, "", superName);
            }
            if (interfaces != null) {
                for (String implemented : interfaces) {
                    useClass(name, "", implemented);
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            useDescriptor(className, "", descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String member = name + descriptor;
            useDescriptor(className, member, descriptor);
            if (exceptions != null) {
                for (String thrown : exceptions) {
                    useClass(className, member, thrown);
                }
            }
            return new MethodScan(className, member);
        }
    }

    private final class MethodScan extends MethodVisitor {
        private final String className;
        private final String member;
        /** The string constant loaded by the instruction just before, which names the library of a load call. */
        private String lastString;

        MethodScan(String className, String member) {
            super(Opcodes.ASM9);
            this.className = className;
            this.member = member;
        }

        @Override
        public void visitLdcInsn(Object value) {
            lastString = value instanceof String text ? text : null;
            if (value instanceof Type type) {
                useDescriptor(className, member, type.getDescriptor());
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            lastString = null;
            useClass(className, member, type);
            if (opcode == Opcodes.NEW) {
                if (THREAD_TYPES.contains(type)) {
                    add(Finding.Codes.THREAD_USE, className, member, "Creates a " + dotted(type) + ".");
                } else if (NETWORK_TYPES.contains(type)) {
                    add(Finding.Codes.NETWORK_USE, className, member, "Creates a " + dotted(type) + ".");
                }
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            lastString = null;
            useClass(className, member, owner);
            useDescriptor(className, member, descriptor);
            if (isSimulatorClass(owner) && !android.hasField(owner, name)) {
                add(Finding.Codes.ANDROID_MEMBER_MISSING, className, member,
                        "Uses the field " + dotted(owner) + "." + name + ", which the simulator's Android layer does not have.");
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            String constant = lastString;
            lastString = null;
            useClass(className, member, owner);
            useDescriptor(className, member, descriptor);
            if (isSimulatorClass(owner) && !android.hasMethod(owner, name, descriptor)) {
                add(Finding.Codes.ANDROID_MEMBER_MISSING, className, member,
                        "Calls " + dotted(owner) + "." + name + "(), which the simulator's Android layer does not have.");
            }
            checkSpecialCall(owner, name, constant);
        }

        private void checkSpecialCall(String callee, String name, String constant) {
            boolean loads = (callee.equals("java/lang/System") || callee.equals("java/lang/Runtime"))
                    && (name.equals("loadLibrary") || name.equals("load"));
            if (loads) {
                add(Finding.Codes.NATIVE_LIBRARY_LOAD, className, member,
                        "Calls " + dotted(callee) + "." + name + "(" + (constant == null ? "" : "\"" + constant + "\"")
                                + "); native libraries built for Android do not load in a desktop JVM.");
            } else if ((callee.equals("java/util/concurrent/Executors") && name.startsWith("new"))
                    || (callee.equals("java/util/concurrent/CompletableFuture") && name.endsWith("Async"))
                    || (callee.equals("java/lang/Thread") && name.equals("start"))
                    || (EXECUTORS.contains(callee) && SUBMITTING.contains(name))) {
                add(Finding.Codes.THREAD_USE, className, member, "Calls " + dotted(callee) + "." + name + "().");
            } else if (callee.equals("java/net/URL") && (name.equals("openConnection") || name.equals("openStream"))) {
                add(Finding.Codes.NETWORK_USE, className, member, "Calls java.net.URL." + name + "().");
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            lastString = null;
            useDescriptor(className, member, descriptor);
            for (Object argument : arguments) {
                if (argument instanceof Handle handle) {
                    useClass(className, member, handle.getOwner());
                    boolean isCall = handle.getTag() >= Opcodes.H_INVOKEVIRTUAL && handle.getTag() <= Opcodes.H_INVOKEINTERFACE;
                    if (isCall && isSimulatorClass(handle.getOwner())
                            && !android.hasMethod(handle.getOwner(), handle.getName(), handle.getDesc())) {
                        add(Finding.Codes.ANDROID_MEMBER_MISSING, className, member,
                                "References " + dotted(handle.getOwner()) + "." + handle.getName()
                                        + "(), which the simulator's Android layer does not have.");
                    }
                } else if (argument instanceof Type type) {
                    useDescriptor(className, member, type.getDescriptor());
                }
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            lastString = null;
            useDescriptor(className, member, descriptor);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                useClass(className, member, type);
            }
        }

        @Override
        public void visitInsn(int opcode) {
            lastString = null;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            lastString = null;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            lastString = null;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            lastString = null;
        }
    }
}
