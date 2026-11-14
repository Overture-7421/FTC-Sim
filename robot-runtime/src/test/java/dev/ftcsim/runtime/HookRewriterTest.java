package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.RobotPhase;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class HookRewriterTest {

    private static byte[] classBytes(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    /** Every call and method-handle target in the class, as {@code owner.name}. */
    private static List<String> references(byte[] bytes) {
        List<String> found = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        found.add(owner + '.' + method);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        for (Object arg : args) {
                            if (arg instanceof Handle handle) {
                                found.add(handle.getOwner() + '.' + handle.getName());
                            }
                        }
                    }
                };
            }
        }, 0);
        return found;
    }

    @Test
    void directCallsAreReplacedByTheHooksFacade() throws IOException {
        byte[] rewritten = new HookRewriter()
                .transform("dev/ftcsim/runtime/fixture/NanoTimeProbe", classBytes(dev.ftcsim.runtime.fixture.NanoTimeProbe.class));

        List<String> references = references(rewritten);
        assertFalse(references.contains("java/lang/System.nanoTime"), references.toString());
        assertTrue(references.contains("dev/ftcsim/contracts/hooks/Hooks.nanoTime"), references.toString());
    }

    @Test
    void methodReferencesAreReplacedInTheirInvokeDynamicArguments() throws IOException {
        byte[] rewritten = new HookRewriter()
                .transform(
                        "dev/ftcsim/runtime/fixture/MethodReferenceProbe",
                        classBytes(dev.ftcsim.runtime.fixture.MethodReferenceProbe.class));

        List<String> references = references(rewritten);
        assertFalse(references.contains("java/lang/System.nanoTime"), references.toString());
        assertTrue(references.contains("dev/ftcsim/contracts/hooks/Hooks.nanoTime"), references.toString());
    }

    @Test
    void monitorCallsAreReplacedWhateverTypeTheReceiverIsDeclaredAs() throws IOException {
        byte[] rewritten = new HookRewriter()
                .transform("dev/ftcsim/runtime/fixture/MonitorProbe", classBytes(dev.ftcsim.runtime.fixture.MonitorProbe.class));

        List<String> references = references(rewritten);
        for (String owner : List.of("java/lang/Object", "dev/ftcsim/runtime/fixture/MonitorProbe")) {
            for (String method : List.of("wait", "notify", "notifyAll")) {
                assertFalse(references.contains(owner + '.' + method), owner + '.' + method + " in " + references);
            }
        }
        for (String hook : List.of("wait", "notify", "notifyAll")) {
            assertTrue(references.contains("dev/ftcsim/contracts/hooks/Hooks." + hook), references.toString());
        }
    }

    @Test
    void aClassWithNothingToRewriteIsReturnedUntouched() throws IOException {
        byte[] original = classBytes(RobotPhase.class);

        assertSame(original, new HookRewriter().transform("dev/ftcsim/contracts/robot/RobotPhase", original));
    }
}
