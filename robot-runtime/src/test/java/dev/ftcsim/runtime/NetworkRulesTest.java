package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.NetworkHooks;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.DatagramSocket;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ServerSocketFactory;
import org.junit.jupiter.api.Test;

/** Keeps the rule table, the JDK and the hooks in step, so a JDK that gains a way onto the network is noticed. */
class NetworkRulesTest {
    private static final List<RewriteRule> RULES = NetworkRules.all();
    private static final Set<String> RULE_MEMBERS =
            RULES.stream().map(rule -> rule.owner() + '.' + rule.name() + rule.descriptor()).collect(Collectors.toSet());

    /** Creating these touches no network, and a client socket's local address is not a listener. */
    private static final Set<String> HARMLESS = Set.of(
            "java/net/Socket.<init>()V",
            "java/net/Socket.<init>(Ljava/net/Proxy;)V",
            "java/net/Socket.bind(Ljava/net/SocketAddress;)V",
            "java/nio/channels/SocketChannel.bind(Ljava/net/SocketAddress;)Ljava/nio/channels/SocketChannel;",
            "java/nio/channels/AsynchronousSocketChannel.bind(Ljava/net/SocketAddress;)Ljava/nio/channels/AsynchronousSocketChannel;");

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static MethodType typeOf(String descriptor) {
        return MethodType.fromMethodDescriptorString(descriptor, NetworkRulesTest.class.getClassLoader());
    }

    @Test
    void everyRuleCallsAHookThatExists() throws ReflectiveOperationException {
        for (RewriteRule rule : RULES) {
            MethodHandles.publicLookup().findStatic(NetworkHooks.class, rule.hookName(), typeOf(rule.hookDescriptor()));
        }
    }

    @Test
    void everyRuleNamesAJdkMemberThatExists() throws ReflectiveOperationException {
        for (RewriteRule rule : RULES) {
            Class<?> owner = Class.forName(rule.owner().replace('/', '.'));
            MethodType type = typeOf(rule.descriptor());
            if (rule.isConstructor()) {
                owner.getConstructor(type.parameterArray());
            } else {
                Method method = owner.getMethod(rule.name(), type.parameterArray());
                assertEquals(type.returnType(), method.getReturnType(), rule.key());
                assertEquals(rule.instance(), !Modifier.isStatic(method.getModifiers()), rule.key());
            }
        }
    }

    @Test
    void everyPublicConstructorOfARedirectedTypeIsRedirectedOrHarmless() {
        for (Class<?> type : List.of(ServerSocket.class, Socket.class, DatagramSocket.class, MulticastSocket.class)) {
            for (Constructor<?> constructor : type.getConstructors()) {
                String member = internalName(type) + ".<init>"
                        + MethodType.methodType(void.class, constructor.getParameterTypes()).toMethodDescriptorString();
                assertTrue(RULE_MEMBERS.contains(member) || HARMLESS.contains(member), member + " can reach the network unnoticed");
            }
        }
    }

    @Test
    void everyWayAServerSocketFactoryListensIsRedirected() {
        for (Method method : ServerSocketFactory.class.getMethods()) {
            if (method.getName().equals("createServerSocket")) {
                String member = internalName(ServerSocketFactory.class) + '.' + method.getName()
                        + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
                assertTrue(RULE_MEMBERS.contains(member), member + " can reach the network unnoticed");
            }
        }
    }

    @Test
    void everyBindConnectOrSendIsRedirectedOrHarmless() {
        List<Class<?>> types = List.of(
                ServerSocket.class,
                Socket.class,
                DatagramSocket.class,
                ServerSocketChannel.class,
                SocketChannel.class,
                DatagramChannel.class,
                AsynchronousServerSocketChannel.class,
                AsynchronousSocketChannel.class);
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                boolean reachesOut = Set.of("bind", "connect", "send").contains(method.getName())
                        || (type == SocketChannel.class
                                && method.getName().equals("open")
                                && Arrays.equals(method.getParameterTypes(), new Class<?>[] {SocketAddress.class}))
                        || (type == ServerSocketChannel.class
                                && method.getName().equals("open")
                                && method.getParameterCount() == 0);
                if (reachesOut && !method.isBridge()) {
                    String member = internalName(type) + '.' + method.getName()
                            + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
                    assertTrue(RULE_MEMBERS.contains(member) || HARMLESS.contains(member), member + " can reach the network unnoticed");
                }
            }
        }
    }
}
