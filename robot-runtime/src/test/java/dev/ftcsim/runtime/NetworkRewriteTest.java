package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.runtime.fixture.ConstructionShapesProbe;
import dev.ftcsim.runtime.fixture.ListenerProbe;
import dev.ftcsim.runtime.fixture.LoopbackProbe;
import dev.ftcsim.runtime.fixture.NetworkMessageProbe;
import dev.ftcsim.runtime.fixture.NetworkProbe;
import dev.ftcsim.runtime.fixture.ServerSocketSubclassProbe;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class NetworkRewriteTest {
    private static final String HOOKS = "dev/ftcsim/contracts/hooks/NetworkHooks";

    @Test
    void noRedirectedMemberSurvivesInTheProgramsThatUseThem() throws IOException {
        for (Class<?> probe : List.of(
                NetworkProbe.class, LoopbackProbe.class, ConstructionShapesProbe.class, NetworkMessageProbe.class, ListenerProbe.class)) {
            List<String> calls = FixtureWorld.calls(FixtureWorld.rewritten(probe));

            for (RewriteRule rule : NetworkRules.all()) {
                String member = rule.owner() + '.' + rule.name() + rule.descriptor();
                assertFalse(calls.contains(member), member + " is still called by " + probe.getSimpleName());
            }
        }
    }

    @Test
    void theHooksTakeTheirPlace() throws IOException {
        List<String> calls = FixtureWorld.calls(FixtureWorld.rewritten(NetworkProbe.class));

        assertTrue(calls.contains(HOOKS + ".newServerSocket(I)Ljava/net/ServerSocket;"), "a constructor becomes a factory");
        assertTrue(calls.contains(HOOKS + ".bind(Ljava/net/ServerSocket;Ljava/net/SocketAddress;)V"), "a method takes its receiver");
        assertTrue(
                calls.contains(HOOKS + ".openSocketChannel(Ljava/net/SocketAddress;)Ljava/nio/channels/SocketChannel;"),
                "a static method keeps its parameters");
        assertFalse(
                calls.contains("javax/net/ssl/SSLServerSocket.bind(Ljava/net/SocketAddress;)V"),
                "a call through a subtype uses the rule of its supertype");
        assertFalse(calls.contains("java/net/ServerSocket.<init>(I)V"));
        assertTrue(
                calls.contains(HOOKS + ".newServerSocket()Ljava/net/ServerSocket;"),
                "an unbound listener is made by the hooks, so that the host can make it a simulated one");
        assertTrue(
                calls.contains(HOOKS + ".openServerSocketChannel()Ljava/nio/channels/ServerSocketChannel;"),
                "so is an unbound listening channel");
    }

    @Test
    void constructorAndMethodReferencesAreRedirectedToo() throws IOException {
        List<String> calls = FixtureWorld.calls(FixtureWorld.rewritten(ConstructionShapesProbe.class));

        assertTrue(calls.contains(HOOKS + ".newServerSocket(IILjava/net/InetAddress;)Ljava/net/ServerSocket;"));
        assertTrue(calls.contains(HOOKS + ".connect(Ljava/net/Socket;Ljava/net/SocketAddress;I)V"));
    }

    @Test
    void aProgramsOwnSubclassOfASocketTypeIsNotRedirected() throws IOException {
        byte[] original = FixtureWorld.classBytes(ServerSocketSubclassProbe.class);

        assertSame(original, new HookRewriter().transform("dev/ftcsim/runtime/fixture/ServerSocketSubclassProbe", original));
    }

    @Test
    void anAllocationThatIsNeverConstructedHereCannotBeFollowedAndIsRejected() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Abandoned", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/net/ServerSocket");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();

        var failure = assertThrows(
                IllegalStateException.class, () -> new HookRewriter().transform("Abandoned", writer.toByteArray()));
        assertTrue(failure.getMessage().contains("java/net/ServerSocket"), failure.getMessage());
    }
}
