package dev.ftcsim.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Loads the fixture programs in a robot world of their own, and reads what the rewriter made of them. */
final class FixtureWorld {
    private static final String PACKAGE = "dev.ftcsim.runtime.fixture.";

    private FixtureWorld() {}

    static RobotClassLoader loader() throws IOException {
        List<Path> classpath = Arrays.stream(System.getProperty("ftcsim.fixtureClasses").split(File.pathSeparator))
                .map(Path::of)
                .toList();
        return new RobotClassLoader(
                classpath, FixtureWorld.class.getClassLoader(), name -> name.startsWith("dev.ftcsim.contracts."), new HookRewriter());
    }

    @SuppressWarnings("unchecked")
    static <T> T run(RobotClassLoader loader, String probe) throws Exception {
        Class<?> type = loader.loadClass(PACKAGE + probe);
        return ((Callable<T>) type.getDeclaredConstructor().newInstance()).call();
    }

    static byte[] classBytes(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    /** What the rewriter turns the class into. */
    static byte[] rewritten(Class<?> type) throws IOException {
        return new HookRewriter().transform(type.getName().replace('.', '/'), classBytes(type));
    }

    /** Every call and method-handle target in the class, as {@code owner.nameDescriptor}. */
    static List<String> calls(byte[] classBytes) {
        List<String> found = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        found.add(owner + '.' + method + desc);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        for (Object arg : args) {
                            if (arg instanceof Handle handle) {
                                found.add(handle.getOwner() + '.' + handle.getName() + handle.getDesc());
                            }
                        }
                    }
                };
            }
        }, 0);
        return found;
    }
}
