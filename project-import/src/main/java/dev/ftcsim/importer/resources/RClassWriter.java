package dev.ftcsim.importer.resources;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.IASTORE;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.NEWARRAY;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.SIPUSH;
import static org.objectweb.asm.Opcodes.T_INT;
import static org.objectweb.asm.Opcodes.V1_8;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Writes the {@code R} classes of one package as bytecode, so that no compiler is needed on the machine that
 * prepares or runs a robot program. The classes are what javac would have made of the generated R.java: a final
 * {@code R} with a nested final class per resource type whose fields are plain {@code static int}s (not
 * constants, as in the R class of an Android library), and a {@code styleable} class whose arrays are
 * initialised by the class.
 */
final class RClassWriter {
    private static final String OBJECT = "java/lang/Object";
    private static final int NESTED = ACC_PUBLIC | ACC_STATIC | ACC_FINAL;
    /** Elements assigned per helper method; each costs about eight bytes of code and a method may hold 64 KiB. */
    private static final int ELEMENTS_PER_METHOD = 3000;
    /** A class file holds at most 65535 constant pool entries and each field needs two. */
    private static final int MAX_FIELDS = 30_000;

    private RClassWriter() {}

    /**
     * @param names   the resources of the package by type, without the styleables
     * @param ids     the id of every resource by {@code type/name}
     * @param arrays  the attribute ids of each styleable
     * @param indexes the position of each styleable attribute in its array
     * @return the class files by path inside a jar, for example {@code com/acme/R$string.class}
     */
    static Map<String, byte[]> write(
            String packageName,
            SortedMap<String, SortedSet<String>> names,
            Map<String, Integer> ids,
            SortedMap<String, int[]> arrays,
            SortedMap<String, Integer> indexes) {
        String outer = (packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/") + "R";
        Map<String, byte[]> classes = new TreeMap<>();
        List<String> types = new ArrayList<>(names.keySet());
        types.add("styleable");
        classes.put(outer + ".class", outerClass(outer, types));
        names.forEach((type, resources) -> {
            if (resources.size() > MAX_FIELDS) {
                throw new ResourceException("R." + type + " of " + packageName + " would have " + resources.size()
                        + " fields, more than a class file can hold");
            }
            classes.put(outer + "$" + type + ".class", resourceClass(outer, type, resources, ids));
        });
        classes.put(outer + "$styleable.class", styleableClass(outer, arrays, indexes));
        return classes;
    }

    private static byte[] outerClass(String outer, List<String> types) {
        ClassWriter writer = newClass(outer);
        for (String type : types) {
            writer.visitInnerClass(outer + "$" + type, outer, type, NESTED);
        }
        privateConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] resourceClass(String outer, String type, SortedSet<String> names, Map<String, Integer> ids) {
        ClassWriter writer = newClass(outer + "$" + type);
        writer.visitInnerClass(outer + "$" + type, outer, type, NESTED);
        privateConstructor(writer);
        for (String name : names) {
            if (isFieldName(name)) {
                intField(writer, name, ids.get(type + "/" + name));
            }
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] styleableClass(String outer, SortedMap<String, int[]> arrays, SortedMap<String, Integer> indexes) {
        String name = outer + "$styleable";
        ClassWriter writer = newClass(name);
        writer.visitInnerClass(name, outer, "styleable", NESTED);
        privateConstructor(writer);
        indexes.forEach((index, position) -> {
            if (isFieldName(index)) {
                intField(writer, index, position);
            }
        });
        arrays.keySet().stream().filter(RClassWriter::isFieldName).forEach(array ->
                writer.visitField(ACC_PUBLIC | ACC_STATIC, array, "[I", null, null).visitEnd());
        writeInitialiser(writer, name, arrays);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code <clinit>} calls helper methods that each fill a few thousand array elements. */
    private static void writeInitialiser(ClassWriter writer, String owner, SortedMap<String, int[]> arrays) {
        List<String> helpers = new ArrayList<>();
        MethodVisitor helper = null;
        int elements = 0;
        for (Map.Entry<String, int[]> array : arrays.entrySet()) {
            if (!isFieldName(array.getKey())) {
                continue;
            }
            if (helper == null || elements >= ELEMENTS_PER_METHOD) {
                if (helper != null) {
                    finish(helper);
                }
                String helperName = "init$" + helpers.size();
                helpers.add(helperName);
                helper = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, helperName, "()V", null, null);
                helper.visitCode();
                elements = 0;
            }
            int[] ids = array.getValue();
            pushInt(helper, ids.length);
            helper.visitIntInsn(NEWARRAY, T_INT);
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != 0) {
                    helper.visitInsn(DUP);
                    pushInt(helper, i);
                    pushInt(helper, ids[i]);
                    helper.visitInsn(IASTORE);
                }
            }
            helper.visitFieldInsn(PUTSTATIC, owner, array.getKey(), "[I");
            elements += ids.length;
        }
        if (helper != null) {
            finish(helper);
            MethodVisitor initialiser = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            initialiser.visitCode();
            for (String name : helpers) {
                initialiser.visitMethodInsn(INVOKESTATIC, owner, name, "()V", false);
            }
            finish(initialiser);
        }
    }

    private static void finish(MethodVisitor method) {
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static ClassWriter newClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, OBJECT, null);
        return writer;
    }

    private static void privateConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
        finish(constructor);
    }

    /** A static int that is not final, with its value as the field's constant so that no initialiser is needed. */
    private static void intField(ClassWriter writer, String name, int value) {
        writer.visitField(ACC_PUBLIC | ACC_STATIC, name, "I", null, value).visitEnd();
    }

    private static void pushInt(MethodVisitor method, int value) {
        if (value == 0) {
            method.visitInsn(ICONST_0);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            method.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            method.visitIntInsn(SIPUSH, value);
        } else {
            method.visitLdcInsn(value);
        }
    }

    /** The JVM allows any name without these characters; resource names are identifiers in practice. */
    private static boolean isFieldName(String name) {
        return !name.isEmpty() && name.chars().noneMatch(c -> ".;[/<>".indexOf(c) >= 0);
    }
}
