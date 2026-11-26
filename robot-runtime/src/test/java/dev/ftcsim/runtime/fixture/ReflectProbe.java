package dev.ftcsim.runtime.fixture;

import dev.ftcsim.runtime.fixture.hidden.HiddenCallsiteFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Exercises Android-compatible reflection from inside the robot class loader. */
public final class ReflectProbe implements Callable<List<String>> {
    public static final String TAG = new String("OriginalTag");
    private static final Object HOLDER = new Object();
    private static final boolean FLAG = false;
    private static final byte BYTE_VAL = 1;
    private static final char CHAR_VAL = 'a';
    private static final short SHORT_VAL = 2;
    private static final int INT_VAL = 3;
    private static final long LONG_VAL = 4L;
    private static final float FLOAT_VAL = 5.5f;
    private static final double DOUBLE_VAL = 6.5;

    static class SamePackage {
        int value = 1;

        String greet(String name) {
            return "hi:" + name;
        }
    }

    @FunctionalInterface
    interface FieldSetter {
        void apply(Field field, Object target, Object value) throws IllegalAccessException;
    }

    @FunctionalInterface
    interface MethodInvoker {
        Object apply(Method method, Object target, Object[] args) throws Exception;
    }

    @Override
    public List<String> call() throws Exception {
        List<String> out = new ArrayList<>();

        // 1. static final without setAccessible(true) must still fail
        Field tag = ReflectProbe.class.getDeclaredField("TAG");
        try {
            tag.set(null, "Denied");
            out.add("tag-unaccessible:allowed");
        } catch (IllegalAccessException expected) {
            out.add("tag-unaccessible:denied");
        }

        // 2. static final reference fields after setAccessible(true) succeed
        tag.setAccessible(true);
        tag.set(null, "ReplacedTag");
        out.add("tag:" + tag.get(null));

        Field holder = ReflectProbe.class.getDeclaredField("HOLDER");
        holder.setAccessible(true);
        FieldSetter setterRef = Field::set;
        setterRef.apply(holder, null, "NewHolder");
        out.add("holder:" + holder.get(null));

        // 3. static final primitive fields and widening after setAccessible(true)
        Field flag = accessible("FLAG");
        flag.setBoolean(null, true);
        out.add("flag:" + flag.getBoolean(null));

        Field b = accessible("BYTE_VAL");
        b.setByte(null, (byte) 7);
        out.add("byte:" + b.getByte(null));

        Field c = accessible("CHAR_VAL");
        c.setChar(null, 'z');
        out.add("char:" + c.getChar(null));

        Field s = accessible("SHORT_VAL");
        s.setShort(null, (short) 12);
        out.add("short:" + s.getShort(null));

        Field i = accessible("INT_VAL");
        i.setInt(null, 42);
        out.add("int:" + i.getInt(null));

        Field l = accessible("LONG_VAL");
        l.setLong(null, 99L);
        out.add("long:" + l.getLong(null));

        Field f = accessible("FLOAT_VAL");
        f.setFloat(null, 1.25f);
        out.add("float:" + f.getFloat(null));

        Field d = accessible("DOUBLE_VAL");
        d.setDouble(null, 2.5);
        out.add("double:" + d.getDouble(null));

        // Widening via Field.set(null, boxed) and type mismatch error
        d.set(null, Integer.valueOf(11));
        out.add("widened:" + d.getDouble(null));
        try {
            i.set(null, null);
            out.add("null-primitive:allowed");
        } catch (IllegalArgumentException expected) {
            out.add("null-primitive:rejected");
        }
        try {
            i.set(null, "not-an-int");
            out.add("bad-type:allowed");
        } catch (IllegalArgumentException expected) {
            out.add("bad-type:rejected");
        }

        // 4. JDK static final field remains immutable even after setAccessible(true)
        Field jdkTrue = Boolean.class.getDeclaredField("TRUE");
        jdkTrue.setAccessible(true);
        try {
            jdkTrue.set(null, Boolean.FALSE);
            out.add("jdk-static-final:allowed");
        } catch (IllegalAccessException expected) {
            out.add("jdk-static-final:denied");
        }

        // 5. Same-package non-final field and package-private method without setAccessible(true) still work
        SamePackage local = new SamePackage();
        Field localField = SamePackage.class.getDeclaredField("value");
        localField.setInt(local, 55);
        out.add("same-pkg-field:" + local.value);
        Method localMethod = SamePackage.class.getDeclaredMethod("greet", String.class);
        out.add("same-pkg-method:" + localMethod.invoke(local, "ok"));

        // 6. Cross-package non-public field / non-public method without setAccessible(true) must still fail
        Class<?> hidden = HiddenCallsiteFactory.hiddenClass();
        Object hiddenObj = HiddenCallsiteFactory.hiddenInstance();
        Field hiddenField = hidden.getDeclaredField("packageField");
        try {
            hiddenField.setInt(hiddenObj, 99);
            out.add("cross-pkg-field:allowed");
        } catch (IllegalAccessException expected) {
            out.add("cross-pkg-field:denied");
        }
        Method hiddenPrivate = hidden.getDeclaredMethod("hiddenStatic");
        try {
            hiddenPrivate.invoke(null);
            out.add("cross-pkg-nonpublic-method:allowed");
        } catch (IllegalAccessException expected) {
            out.add("cross-pkg-nonpublic-method:denied");
        }

        // 7. Cross-package public method on a package-private robot-world class succeeds without setAccessible(true)
        Method publicStatic = hidden.getDeclaredMethod("ping", String.class);
        out.add("hidden-public-static:" + publicStatic.invoke(null, "a"));
        Method publicInstance = hidden.getDeclaredMethod("instancePing", String.class);
        MethodInvoker invokerRef = Method::invoke;
        out.add("hidden-public-instance:" + invokerRef.apply(publicInstance, hiddenObj, new Object[] {"b"}));

        return out;
    }

    private static Field accessible(String name) throws NoSuchFieldException {
        Field field = ReflectProbe.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
