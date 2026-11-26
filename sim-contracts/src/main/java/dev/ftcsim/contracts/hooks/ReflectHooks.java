package dev.ftcsim.contracts.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Entry points that rewritten robot bytecode calls instead of {@link Field}{@code .set*} and
 * {@link Method#invoke(Object, Object...)}, to match the two ways Android's ART runtime is more permissive than
 * HotSpot for code inside the robot world:
 *
 * <ul>
 *   <li>Once {@link AccessibleObject#setAccessible(boolean) setAccessible(true)} has been called on a {@link Field},
 *       ART allows writing {@code static final} fields ({@code art/runtime/native/java_lang_reflect_Field.cc});
 *       HotSpot forbids it unconditionally.
 *   <li>When a {@link Method} itself is {@code public}, ART's {@code VerifyAccess} allows invoking it even when its
 *       declaring class is non-public ({@code art/runtime/reflection.cc}); HotSpot checks the declaring class too.
 * </ul>
 *
 * <p>Both relaxations apply only to classes loaded inside the robot world ({@link Hooks#WORLD_LOADER_NAME} and its
 * child class loaders), never to JDK or host classes. Every other call preserves standard Java access checks for the
 * calling robot class so redirecting through this host class does not break same-package or nestmate reflection.
 */
public final class ReflectHooks {
    private static final StackWalker CALLER_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private ReflectHooks() {}

    // java.lang.reflect.Field ---------------------------------------------------------------------------------

    public static void set(Field field, Object target, Object value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalObject(field, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.set(target, value);
        } else {
            field.set(target, value);
        }
    }

    public static void setBoolean(Field field, Object target, boolean value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalBoolean(field, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setBoolean(target, value);
        } else {
            field.setBoolean(target, value);
        }
    }

    public static void setByte(Field field, Object target, byte value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalIntegral(field, byte.class, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setByte(target, value);
        } else {
            field.setByte(target, value);
        }
    }

    public static void setChar(Field field, Object target, char value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalChar(field, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setChar(target, value);
        } else {
            field.setChar(target, value);
        }
    }

    public static void setShort(Field field, Object target, short value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalIntegral(field, short.class, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setShort(target, value);
        } else {
            field.setShort(target, value);
        }
    }

    public static void setInt(Field field, Object target, int value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalIntegral(field, int.class, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setInt(target, value);
        } else {
            field.setInt(target, value);
        }
    }

    public static void setLong(Field field, Object target, long value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalIntegral(field, long.class, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setLong(target, value);
        } else {
            field.setLong(target, value);
        }
    }

    public static void setFloat(Field field, Object target, float value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalFloat(field, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setFloat(target, value);
        } else {
            field.setFloat(target, value);
        }
    }

    public static void setDouble(Field field, Object target, double value) throws IllegalAccessException {
        Objects.requireNonNull(field, "field");
        if (canWriteStaticFinal(field)) {
            writeStaticFinalDouble(field, value);
            return;
        }
        Field accessible = accessibleForCaller(field, target);
        if (accessible != null) {
            accessible.setDouble(target, value);
        } else {
            field.setDouble(target, value);
        }
    }

    // java.lang.reflect.Method --------------------------------------------------------------------------------

    public static Object invoke(Method method, Object target, Object... args)
            throws IllegalAccessException, InvocationTargetException {
        Objects.requireNonNull(method, "method");
        if (isOverrideSet(method)) {
            return method.invoke(target, args);
        }
        Class<?> declaring = method.getDeclaringClass();
        int modifiers = method.getModifiers();
        if (Modifier.isPublic(modifiers)) {
            if (Modifier.isPublic(declaring.getModifiers())) {
                return method.invoke(target, args);
            }
            if (isRobotWorldClass(declaring)) {
                return accessibleCopy(method).invoke(target, args);
            }
        }
        Class<?> caller = callerClass();
        if (caller != null && hasMemberAccess(caller, method, target)) {
            return accessibleCopy(method).invoke(target, args);
        }
        return method.invoke(target, args);
    }

    // Static final writes -------------------------------------------------------------------------------------

    private static boolean canWriteStaticFinal(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers)
                && Modifier.isFinal(modifiers)
                && isOverrideSet(field)
                && isRobotWorldClass(field.getDeclaringClass());
    }

    private static void writeStaticFinalObject(Field field, Object value) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type.isPrimitive()) {
            if (value == null) {
                throw illegalFieldValue(field, "null");
            }
            if (value instanceof Boolean b) {
                writeStaticFinalBoolean(field, b);
            } else if (value instanceof Byte b) {
                writeStaticFinalIntegral(field, byte.class, b);
            } else if (value instanceof Character c) {
                writeStaticFinalChar(field, c);
            } else if (value instanceof Short s) {
                writeStaticFinalIntegral(field, short.class, s);
            } else if (value instanceof Integer i) {
                writeStaticFinalIntegral(field, int.class, i);
            } else if (value instanceof Long l) {
                writeStaticFinalIntegral(field, long.class, l);
            } else if (value instanceof Float f) {
                writeStaticFinalFloat(field, f);
            } else if (value instanceof Double d) {
                writeStaticFinalDouble(field, d);
            } else {
                throw illegalFieldValue(field, value.getClass().getName());
            }
            return;
        }
        if (value != null && !type.isInstance(value)) {
            throw illegalFieldValue(field, value.getClass().getName());
        }
        ensureInitialized(field);
        UnsafeAccess.putObjectVolatile(field, value);
    }

    private static void writeStaticFinalBoolean(Field field, boolean value) throws IllegalAccessException {
        if (field.getType() != boolean.class) {
            throw illegalFieldValue(field, "boolean");
        }
        ensureInitialized(field);
        UnsafeAccess.putBooleanVolatile(field, value);
    }

    private static void writeStaticFinalChar(Field field, char value) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == char.class) {
            ensureInitialized(field);
            UnsafeAccess.putCharVolatile(field, value);
        } else if (type == int.class) {
            ensureInitialized(field);
            UnsafeAccess.putIntVolatile(field, value);
        } else if (type == long.class) {
            ensureInitialized(field);
            UnsafeAccess.putLongVolatile(field, value);
        } else if (type == float.class) {
            ensureInitialized(field);
            UnsafeAccess.putFloatVolatile(field, value);
        } else if (type == double.class) {
            ensureInitialized(field);
            UnsafeAccess.putDoubleVolatile(field, value);
        } else {
            throw illegalFieldValue(field, "char");
        }
    }

    private static void writeStaticFinalIntegral(Field field, Class<?> source, long value) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == byte.class && source == byte.class) {
            ensureInitialized(field);
            UnsafeAccess.putByteVolatile(field, (byte) value);
        } else if (type == short.class && (source == byte.class || source == short.class)) {
            ensureInitialized(field);
            UnsafeAccess.putShortVolatile(field, (short) value);
        } else if (type == int.class && (source == byte.class || source == short.class || source == int.class)) {
            ensureInitialized(field);
            UnsafeAccess.putIntVolatile(field, (int) value);
        } else if (type == long.class) {
            ensureInitialized(field);
            UnsafeAccess.putLongVolatile(field, value);
        } else if (type == float.class) {
            ensureInitialized(field);
            UnsafeAccess.putFloatVolatile(field, (float) value);
        } else if (type == double.class) {
            ensureInitialized(field);
            UnsafeAccess.putDoubleVolatile(field, (double) value);
        } else {
            throw illegalFieldValue(field, source.getName());
        }
    }

    private static void writeStaticFinalFloat(Field field, float value) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == float.class) {
            ensureInitialized(field);
            UnsafeAccess.putFloatVolatile(field, value);
        } else if (type == double.class) {
            ensureInitialized(field);
            UnsafeAccess.putDoubleVolatile(field, value);
        } else {
            throw illegalFieldValue(field, "float");
        }
    }

    private static void writeStaticFinalDouble(Field field, double value) throws IllegalAccessException {
        if (field.getType() != double.class) {
            throw illegalFieldValue(field, "double");
        }
        ensureInitialized(field);
        UnsafeAccess.putDoubleVolatile(field, value);
    }

    /** Reading the accessible static field guarantees its declaring class has completed static initialization. */
    private static void ensureInitialized(Field field) throws IllegalAccessException {
        field.get(null);
    }

    private static IllegalArgumentException illegalFieldValue(Field field, String givenType) {
        return new IllegalArgumentException(
                "Can not set " + field.getType().getName() + " field " + field.getDeclaringClass().getName()
                        + "." + field.getName() + " to " + givenType);
    }

    // Access checks -------------------------------------------------------------------------------------------

    /**
     * Checks the {@code override} flag set by {@link AccessibleObject#setAccessible(boolean)} rather than
     * {@code canAccess}: {@code canAccess(null)} returns true for a {@code public static final} field even when
     * {@code setAccessible(true)} was never called, and neither ART nor HotSpot allows writing a final field without it.
     */
    @SuppressWarnings("deprecation")
    private static boolean isOverrideSet(AccessibleObject object) {
        return object.isAccessible();
    }

    private static boolean isRobotWorldClass(Class<?> type) {
        for (ClassLoader loader = type.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (Hooks.WORLD_LOADER_NAME.equals(loader.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an accessible copy of {@code field} only when {@code field} is non-final, was not marked accessible,
     * is not already globally accessible, and the immediate robot caller has standard Java language access to it.
     */
    private static Field accessibleForCaller(Field field, Object target) {
        if (isOverrideSet(field)) {
            return null;
        }
        int modifiers = field.getModifiers();
        Class<?> declaring = field.getDeclaringClass();
        if (Modifier.isFinal(modifiers)) {
            return null;
        }
        if (Modifier.isPublic(modifiers) && Modifier.isPublic(declaring.getModifiers())) {
            return null;
        }
        Class<?> caller = callerClass();
        if (caller == null || !hasMemberAccess(caller, field, target)) {
            return null;
        }
        return accessibleCopy(field);
    }

    private static Class<?> callerClass() {
        return CALLER_WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != ReflectHooks.class)
                .findFirst()
                .orElse(null));
    }

    private static boolean hasMemberAccess(Class<?> caller, Member member, Object target) {
        Class<?> declaring = member.getDeclaringClass();
        if (!hasClassAccess(caller, declaring)) {
            return false;
        }
        int modifiers = member.getModifiers();
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        if (Modifier.isPrivate(modifiers)) {
            return caller == declaring || caller.isNestmateOf(declaring);
        }
        boolean samePackage = sameRuntimePackage(caller, declaring);
        if (samePackage) {
            return true;
        }
        if (Modifier.isProtected(modifiers) && declaring.isAssignableFrom(caller)) {
            if (Modifier.isStatic(modifiers)) {
                return true;
            }
            return target == null || caller.isInstance(target);
        }
        return false;
    }

    private static boolean hasClassAccess(Class<?> caller, Class<?> target) {
        if (Modifier.isPublic(target.getModifiers())) {
            return target.getModule() == caller.getModule()
                    || target.getModule().isExported(target.getPackageName(), caller.getModule());
        }
        return sameRuntimePackage(caller, target);
    }

    private static boolean sameRuntimePackage(Class<?> first, Class<?> second) {
        return first.getClassLoader() == second.getClassLoader()
                && first.getPackageName().equals(second.getPackageName());
    }

    private static Field accessibleCopy(Field field) {
        try {
            Field copy = field.getDeclaringClass().getDeclaredField(field.getName());
            copy.setAccessible(true);
            return copy;
        } catch (NoSuchFieldException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Method accessibleCopy(Method method) {
        try {
            Method copy = method.getDeclaringClass().getDeclaredMethod(method.getName(), method.getParameterTypes());
            copy.setAccessible(true);
            return copy;
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Reflective bridge to {@code sun.misc.Unsafe} so {@code sim-contracts} has no compile-time dependency on
     * {@code jdk.unsupported}. Used exclusively to write {@code static final} fields of robot-world classes after
     * {@code Field.setAccessible(true)}, matching Android ART.
     */
    private static final class UnsafeAccess {
        private static final Object UNSAFE;
        private static final MethodHandle STATIC_FIELD_BASE;
        private static final MethodHandle STATIC_FIELD_OFFSET;
        private static final MethodHandle PUT_OBJECT;
        private static final MethodHandle PUT_BOOLEAN;
        private static final MethodHandle PUT_BYTE;
        private static final MethodHandle PUT_CHAR;
        private static final MethodHandle PUT_SHORT;
        private static final MethodHandle PUT_INT;
        private static final MethodHandle PUT_LONG;
        private static final MethodHandle PUT_FLOAT;
        private static final MethodHandle PUT_DOUBLE;

        static {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                UNSAFE = theUnsafe.get(null);
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                STATIC_FIELD_BASE = bind(lookup, unsafeClass, "staticFieldBase", Field.class);
                STATIC_FIELD_OFFSET = bind(lookup, unsafeClass, "staticFieldOffset", Field.class);
                PUT_OBJECT = bind(lookup, unsafeClass, "putObjectVolatile", Object.class, long.class, Object.class);
                PUT_BOOLEAN = bind(lookup, unsafeClass, "putBooleanVolatile", Object.class, long.class, boolean.class);
                PUT_BYTE = bind(lookup, unsafeClass, "putByteVolatile", Object.class, long.class, byte.class);
                PUT_CHAR = bind(lookup, unsafeClass, "putCharVolatile", Object.class, long.class, char.class);
                PUT_SHORT = bind(lookup, unsafeClass, "putShortVolatile", Object.class, long.class, short.class);
                PUT_INT = bind(lookup, unsafeClass, "putIntVolatile", Object.class, long.class, int.class);
                PUT_LONG = bind(lookup, unsafeClass, "putLongVolatile", Object.class, long.class, long.class);
                PUT_FLOAT = bind(lookup, unsafeClass, "putFloatVolatile", Object.class, long.class, float.class);
                PUT_DOUBLE = bind(lookup, unsafeClass, "putDoubleVolatile", Object.class, long.class, double.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static MethodHandle bind(
                MethodHandles.Lookup lookup, Class<?> unsafeClass, String name, Class<?>... parameterTypes)
                throws NoSuchMethodException, IllegalAccessException {
            return lookup.unreflect(unsafeClass.getMethod(name, parameterTypes)).bindTo(UNSAFE);
        }

        private static Object base(Field field) {
            try {
                return STATIC_FIELD_BASE.invoke(field);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        private static long offset(Field field) {
            try {
                return (long) STATIC_FIELD_OFFSET.invoke(field);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putObjectVolatile(Field field, Object value) {
            try {
                PUT_OBJECT.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putBooleanVolatile(Field field, boolean value) {
            try {
                PUT_BOOLEAN.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putByteVolatile(Field field, byte value) {
            try {
                PUT_BYTE.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putCharVolatile(Field field, char value) {
            try {
                PUT_CHAR.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putShortVolatile(Field field, short value) {
            try {
                PUT_SHORT.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putIntVolatile(Field field, int value) {
            try {
                PUT_INT.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putLongVolatile(Field field, long value) {
            try {
                PUT_LONG.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putFloatVolatile(Field field, float value) {
            try {
                PUT_FLOAT.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        static void putDoubleVolatile(Field field, double value) {
            try {
                PUT_DOUBLE.invoke(base(field), offset(field), value);
            } catch (Throwable t) {
                throw propagate(t);
            }
        }

        private static RuntimeException propagate(Throwable t) {
            if (t instanceof RuntimeException runtime) {
                return runtime;
            }
            if (t instanceof Error error) {
                throw error;
            }
            return new IllegalStateException("Cannot write static final field via Unsafe", t);
        }
    }
}
