package android;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/** Builds the failure for Android members the simulator deliberately does not provide. */
public final class Unsupported {
    private Unsupported() {}

    public static UnsupportedOperationException member(String type, String member) {
        return new UnsupportedOperationException(
                type + "#" + member + " is an Android API that FTC-Sim does not provide. "
                        + "The robot code or a library it uses reached a part of the Android framework that has no "
                        + "desktop equivalent yet; this is a simulator gap, not a mistake in the robot code.");
    }

    /**
     * An object of an interface type whose every member fails like {@link #member}. For factories whose product
     * is only ever held, not used, by the code that runs on the simulated controller.
     */
    public static <T> T object(Class<T> type) {
        InvocationHandler failing = (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "unsupported " + type.getName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> proxy == arguments[0];
                };
            }
            String signature = method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList();
            throw member(type.getName(), signature.replace('[', '(').replace(']', ')'));
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, failing));
    }
}
