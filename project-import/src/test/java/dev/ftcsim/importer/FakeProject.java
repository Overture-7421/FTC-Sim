package dev.ftcsim.importer;

import static dev.ftcsim.importer.Fixtures.AUTONOMOUS;
import static dev.ftcsim.importer.Fixtures.DISABLED;
import static dev.ftcsim.importer.Fixtures.OP_MODE;
import static dev.ftcsim.importer.Fixtures.TELEOP;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.objectweb.asm.MethodVisitor;

/**
 * A team project and the output of the build that would have been made from it, for the import tests, which start a
 * fake Gradle wrapper (see {@code org.gradle.wrapper.GradleWrapperMain} in the test sources) instead of the real one.
 * The project has two OpModes in its application module, one in a library module of the project, a module
 * with only a disabled sample, and the SDK as an AAR; every file the build reports is made here.
 */
final class FakeProject {
    static final String LINEAR_OP_MODE = "com/qualcomm/robotcore/eventloop/opmode/LinearOpMode";

    final Path root;
    final Path builds;
    final Path sdk;
    final Path shim;
    private final Properties control = new Properties();
    private final List<String> export = new ArrayList<>();

    FakeProject(Path temp) throws IOException {
        root = temp.resolve("project");
        builds = temp.resolve("builds");
        sdk = Files.createDirectories(temp.resolve("android-sdk"));
        write("settings.gradle", "include ':TeamCode'");
        write("build.gradle", "// root build");
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=none");
        write("TeamCode/build.gradle", "apply plugin: 'com.android.application'");
        write("TeamCode/src/main/java/org/team/Drive.java", "package org.team; public class Drive {}");
        write("TeamCode/src/main/res/values/strings.xml", Fixtures.values("<string name=\"team_name\">Team 7421</string>"));
        write("TeamCode/src/main/assets/team.txt", "assets of the team");
        write("local.properties", "sdk.dir=C\\:\\\\Users\\\\someone-else\\\\Sdk");
        wrapper();
        shim = Fixtures.classFolder(temp.resolve("shim"), Map.of(
                "android/util/Log", Fixtures.classFile("android/util/Log", "java/lang/Object", ACC_PUBLIC, writer -> {})));
        buildOutputs();
    }

    private void write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** The jar of the fake wrapper: the one class of the test sources that stands in for Gradle's. */
    private void wrapper() throws IOException {
        byte[] bytes;
        try (InputStream in = FakeProject.class.getResourceAsStream("/org/gradle/wrapper/GradleWrapperMain.class")) {
            bytes = in.readAllBytes();
        }
        Path jar = root.resolve("gradle/wrapper/gradle-wrapper.jar");
        Fixtures.jar(jar, Map.of("org/gradle/wrapper/GradleWrapperMain.class", bytes));
    }

    private void buildOutputs() throws IOException {
        Path appClasses = Fixtures.classFolder(builds.resolve("app-classes"), Map.of(
                "org/team/Drive", Fixtures.classFile("org/team/Drive", LINEAR_OP_MODE, ACC_PUBLIC, writer -> {
                    Fixtures.annotate(writer, TELEOP, Map.of("name", "Drive", "group", "Main"));
                    MethodVisitor run = writer.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
                    run.visitCode();
                    run.visitMethodInsn(INVOKESTATIC, "android/graphics/Bitmap", "createBitmap", "()V", false);
                    run.visitInsn(RETURN);
                    run.visitMaxs(0, 0);
                    run.visitEnd();
                }),
                "org/team/Park", Fixtures.annotated("org/team/Park", OP_MODE, AUTONOMOUS, Map.of("name", "Park"))));
        Path robotCore = Fixtures.classJar(builds.resolve("RobotCore-runtime.jar"), Map.of(
                OP_MODE, Fixtures.plainClass(OP_MODE, "java/lang/Object"),
                LINEAR_OP_MODE, Fixtures.plainClass(LINEAR_OP_MODE, OP_MODE)));
        Path common = Fixtures.classJar(builds.resolve("common-classes.jar"), Map.of(
                "org/common/CommonAuto", Fixtures.annotated("org/common/CommonAuto", LINEAR_OP_MODE, AUTONOMOUS, Map.of("name", "Common Auto"))));
        Path samples = Fixtures.classJar(builds.resolve("samples-classes.jar"), Map.of(
                "org/samples/Sample", Fixtures.classFile("org/samples/Sample", OP_MODE, ACC_PUBLIC, writer -> {
                    Fixtures.annotate(writer, TELEOP, Map.of("name", "Sample"));
                    Fixtures.annotate(writer, DISABLED, Map.of());
                })));
        Path pedro = Fixtures.classJar(builds.resolve("pedro.jar"), Map.of("com/pedropathing/Follower", Fixtures.plainClass("com/pedropathing/Follower", "java/lang/Object")));
        Path robotCoreAar = Fixtures.aar(
                builds.resolve("RobotCore-11.1.0.aar"),
                "org.firstinspires.ftc.robotcore",
                "int string app_name 0x7f010000\nint raw sample 0x7f020000\n",
                Map.of("res/values/values.xml", Fixtures.values("<string name=\"app_name\">Robot Controller</string>"),
                        "assets/sdk/schema.xsd", "<schema/>",
                        "jni/arm64-v8a/libRobotCore.so", "ELF"));

        // The class jars above have no Java resources, as the Android Gradle plugin's own; the jars as published do.
        Path appJavaRes = Files.createDirectories(builds.resolve("app-java-res"));
        Files.writeString(appJavaRes.resolve("team.properties"), "team=7421");
        Files.writeString(appJavaRes.resolve("conflict.txt"), "from the app");
        Path commonJavaRes = Files.createDirectories(builds.resolve("common-java-res"));
        Files.writeString(commonJavaRes.resolve("common.txt"), "common");
        Files.writeString(commonJavaRes.resolve("conflict.txt"), "from common");
        Path robotCorePublished = Fixtures.jar(builds.resolve("RobotCore-published.jar"), Map.of(
                OP_MODE + ".class", Fixtures.plainClass(OP_MODE, "java/lang/Object"),
                "sdk/version.txt", "11.1.0".getBytes(StandardCharsets.UTF_8),
                "META-INF/services/com.acme.Provider", "org.rc.RcProvider".getBytes(StandardCharsets.UTF_8),
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8),
                "META-INF/LICENSE", "a license".getBytes(StandardCharsets.UTF_8)));
        Path pedroPublished = Fixtures.jar(builds.resolve("pedro-published.jar"), Map.of(
                "com/pedropathing/Follower.class", Fixtures.plainClass("com/pedropathing/Follower", "java/lang/Object"),
                "decode-dark.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'},
                "META-INF/services/com.acme.Provider", "com.pedropathing.PedroProvider\n".getBytes(StandardCharsets.UTF_8),
                "conflict.txt", "from pedro".getBytes(StandardCharsets.UTF_8)));

        export.add("ftcsim-export\t1");
        export.add("gradle\t8.9");
        export.add("variant\tdebug");
        export.add("application\t:TeamCode\torg.team\t" + appJavaRes);
        export.add("classes\t" + appClasses);
        library("project", ":Common", common, null, commonJavaRes);
        library("project", ":Samples", samples, null);
        library("module", "org.firstinspires.ftc:RobotCore:11.1.0", robotCore, robotCoreAar, robotCorePublished);
        library("module", "com.pedropathing:ftc:2.1.1", pedro, null, pedroPublished);
        export.add("module\t:Common\tlibrary\torg.common");
        export.add("module\t:Samples\tlibrary\torg.samples");
        export.add("module\t:TeamCode\tapplication\torg.team");
        export.add("res\t:TeamCode\t${cwd}\\TeamCode\\src\\main\\res");
        export.add("assets\t:TeamCode\t${cwd}\\TeamCode\\src\\main\\assets");
    }

    private void library(String origin, String id, Path classes, Path aar, Path... javaResources) {
        StringBuilder line = new StringBuilder("library\t" + origin + "\t" + id + "\t" + classes + "\t" + (aar == null ? "-" : aar));
        for (Path javaResource : javaResources) {
            line.append('\t').append(javaResource);
        }
        export.add(line.toString());
    }

    /** Replaces the lines of the export that the fake build writes; the lines may use {@code ${cwd}} for the copy of the project. */
    FakeProject withExport(List<String> lines) {
        export.clear();
        export.addAll(lines);
        return this;
    }

    /** The same build as a project would report it whose libraries have no Java resources. */
    FakeProject withoutJavaResources() {
        export.replaceAll(line -> {
            String[] fields = line.split("\t", -1);
            int known = fields[0].equals("application") ? 3 : fields[0].equals("library") ? 5 : fields.length;
            return String.join("\t", Arrays.copyOf(fields, Math.min(known, fields.length)));
        });
        return this;
    }

    FakeProject control(String key, Object value) {
        control.setProperty(key, value.toString());
        return this;
    }

    /** Writes what the fake build will do; call after the last {@link #control}. */
    FakeProject ready() throws IOException {
        Path exportFile = builds.resolve("export-template.txt");
        Files.write(exportFile, export, StandardCharsets.UTF_8);
        if (!control.containsKey("printFile")) {
            control.setProperty("exportFile", exportFile.toString());
        }
        try (Writer out = Files.newBufferedWriter(root.resolve("fake-gradle.properties"), StandardCharsets.UTF_8)) {
            control.store(out, null);
        }
        return this;
    }

    ImportRequest request(Path temp) {
        return ImportRequest.of(
                        root,
                        temp.resolve("work"),
                        temp.resolve("package"),
                        Path.of(System.getProperty("java.home")),
                        sdk,
                        BuildTrust.TRUST_BUILD_SCRIPTS)
                .withSimulatorClasspath(List.of(shim));
    }
}
