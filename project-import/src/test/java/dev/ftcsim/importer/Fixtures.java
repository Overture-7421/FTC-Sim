package dev.ftcsim.importer;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/** Class files, jars and AARs for tests, made on the spot so that each test can say exactly what its program contains. */
public final class Fixtures {
    public static final String OP_MODE = "com/qualcomm/robotcore/eventloop/opmode/OpMode";
    public static final String TELEOP = "Lcom/qualcomm/robotcore/eventloop/opmode/TeleOp;";
    public static final String AUTONOMOUS = "Lcom/qualcomm/robotcore/eventloop/opmode/Autonomous;";
    public static final String DISABLED = "Lcom/qualcomm/robotcore/eventloop/opmode/Disabled;";

    private Fixtures() {}

    /** A class with a default constructor; {@code body} adds annotations, methods or inner-class entries. */
    public static byte[] classFile(String internalName, String superName, int access, Consumer<ClassWriter> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, access | ACC_SUPER, internalName, null, superName, null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        body.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] plainClass(String internalName, String superName) {
        return classFile(internalName, superName, ACC_PUBLIC, writer -> {});
    }

    /** A public class annotated as an OpMode, with the annotation's named string attributes. */
    public static byte[] annotated(String internalName, String superName, String annotation, Map<String, String> attributes) {
        return classFile(internalName, superName, ACC_PUBLIC, writer -> annotate(writer, annotation, attributes));
    }

    public static void annotate(ClassWriter writer, String annotation, Map<String, String> attributes) {
        AnnotationVisitor visitor = writer.visitAnnotation(annotation, true);
        attributes.forEach(visitor::visit);
        visitor.visitEnd();
    }

    /** A public class whose method {@code run()V} is written by {@code code}. */
    public static byte[] withMethod(String internalName, Consumer<MethodVisitor> code) {
        return classFile(internalName, "java/lang/Object", ACC_PUBLIC, writer -> {
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
            method.visitCode();
            code.accept(method);
            method.visitInsn(RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        });
    }

    /** Writes a jar of the given entries (name to bytes), in name order. */
    public static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    /** A jar of classes, named {@code a/b/C} without the suffix. */
    public static Path classJar(Path file, Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        classes.forEach((name, bytes) -> entries.put(name + ".class", bytes));
        return jar(file, entries);
    }

    /** A folder of class files, named {@code a/b/C} without the suffix. */
    public static Path classFolder(Path folder, Map<String, byte[]> classes) throws IOException {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Path file = folder.resolve(entry.getKey() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        Files.createDirectories(folder);
        return folder;
    }

    /**
     * An Android library archive.
     *
     * @param rTxt  the text of {@code R.txt}
     * @param files other entries by name, such as {@code res/values/strings.xml} or {@code assets/a.txt}
     */
    public static Path aar(Path file, String packageName, String rTxt, Map<String, String> files) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        entries.put("AndroidManifest.xml", ("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\""
                + packageName + "\"/>").getBytes(StandardCharsets.UTF_8));
        entries.put("R.txt", rTxt.getBytes(StandardCharsets.UTF_8));
        files.forEach((name, text) -> entries.put(name, text.getBytes(StandardCharsets.UTF_8)));
        return jar(file, entries);
    }

    /** A {@code values} file with the given elements, each a complete XML element. */
    public static String values(String... elements) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" + String.join("\n", elements) + "\n</resources>\n";
    }

    /**
     * The text of a manifest of format version 1, which is what importer revision 1 wrote, made from the text of a
     * current one that has no Java resources: version 1 had neither the importer revision nor the Java resources jar.
     */
    public static String asVersion1Manifest(String currentJson) {
        String version1 = currentJson
                .replace("\"formatVersion\" : 2", "\"formatVersion\" : 1")
                .replaceFirst("\\s*\"importerRevision\" : \\d+,", "")
                .replaceFirst(",\\s*\"javaResources\" : \"\"", "");
        if (version1.equals(currentJson) || version1.contains("importerRevision") || version1.contains("javaResources")) {
            throw new IllegalArgumentException("not the manifest of a package without Java resources:\n" + currentJson);
        }
        return version1;
    }
}
