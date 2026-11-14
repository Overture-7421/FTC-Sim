package dev.ftcsim.importer.scan;

import static dev.ftcsim.importer.Fixtures.AUTONOMOUS;
import static dev.ftcsim.importer.Fixtures.DISABLED;
import static dev.ftcsim.importer.Fixtures.OP_MODE;
import static dev.ftcsim.importer.Fixtures.TELEOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.importer.Fixtures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.MethodVisitor;

class ProgramScanTest {
    private static final String PKG = "org/team/";

    private static Map<String, String> named(String name, String group) {
        return group.isEmpty() ? Map.of("name", name) : Map.of("name", name, "group", group);
    }

    private static Path library(Path folder) throws IOException {
        return Fixtures.classJar(folder.resolve("RobotCore.jar"), Map.of(
                OP_MODE, Fixtures.plainClass(OP_MODE, "java/lang/Object"),
                "com/qualcomm/robotcore/eventloop/opmode/LinearOpMode", Fixtures.plainClass("com/qualcomm/robotcore/eventloop/opmode/LinearOpMode", OP_MODE)));
    }

    @Test
    void findsOpModesByTheRulesOfTheSdksOwnScan(@TempDir Path temp) throws IOException {
        Path program = Fixtures.classFolder(temp.resolve("classes"), Map.ofEntries(
                Map.entry(PKG + "Drive", Fixtures.annotated(PKG + "Drive", "com/qualcomm/robotcore/eventloop/opmode/LinearOpMode", TELEOP, named("Drive", "Main"))),
                Map.entry(PKG + "Park", Fixtures.annotated(PKG + "Park", OP_MODE, AUTONOMOUS, named("Park", ""))),
                Map.entry(PKG + "Unnamed", Fixtures.annotated(PKG + "Unnamed", OP_MODE, TELEOP, Map.of())),
                Map.entry(PKG + "Helper", Fixtures.plainClass(PKG + "Helper", OP_MODE)),
                Map.entry(PKG + "Hidden", Fixtures.classFile(PKG + "Hidden", OP_MODE, ACC_PUBLIC, writer -> {
                    Fixtures.annotate(writer, TELEOP, named("Hidden", ""));
                    Fixtures.annotate(writer, DISABLED, Map.of());
                })),
                Map.entry(PKG + "Both", Fixtures.classFile(PKG + "Both", OP_MODE, ACC_PUBLIC, writer -> {
                    Fixtures.annotate(writer, TELEOP, named("Both", ""));
                    Fixtures.annotate(writer, AUTONOMOUS, named("Both", ""));
                })),
                Map.entry(PKG + "NotAnOpMode", Fixtures.annotated(PKG + "NotAnOpMode", "java/lang/Object", TELEOP, named("Nope", ""))),
                Map.entry(PKG + "Hidden2", Fixtures.classFile(PKG + "Hidden2", OP_MODE, 0, writer -> Fixtures.annotate(writer, TELEOP, named("Package private", "")))),
                Map.entry(PKG + "System", Fixtures.annotated(PKG + "System", OP_MODE, TELEOP, named("$Stop$", ""))),
                Map.entry(PKG + "Outer$Inner", Fixtures.classFile(PKG + "Outer$Inner", OP_MODE, ACC_PUBLIC, writer -> {
                    writer.visitInnerClass(PKG + "Outer$Inner", PKG + "Outer", "Inner", ACC_PUBLIC);
                    Fixtures.annotate(writer, TELEOP, named("Inner", ""));
                })),
                Map.entry(PKG + "Outer$Nested", Fixtures.classFile(PKG + "Outer$Nested", OP_MODE, ACC_PUBLIC, writer -> {
                    writer.visitInnerClass(PKG + "Outer$Nested", PKG + "Outer", "Nested", ACC_PUBLIC | ACC_STATIC);
                    Fixtures.annotate(writer, TELEOP, named("", ""));
                }))));

        try (ProgramScan scan = ProgramScan.open(List.of(program, library(temp)))) {
            ProgramScan.OpModes result = scan.opModesIn(List.of(program));

            assertEquals(List.of(
                            new OpModeDescriptor("Nested", "", OpModeKind.TELEOP, "org.team.Outer$Nested"),
                            new OpModeDescriptor("Park", "", OpModeKind.AUTONOMOUS, "org.team.Park"),
                            new OpModeDescriptor("Unnamed", "", OpModeKind.TELEOP, "org.team.Unnamed"),
                            new OpModeDescriptor("Drive", "Main", OpModeKind.TELEOP, "org.team.Drive")),
                    result.found());
            List<String> rejected = result.notes().stream().map(Finding::subject).sorted().toList();
            assertEquals(
                    List.of("org.team.Both", "org.team.Hidden2", "org.team.NotAnOpMode", "org.team.Outer$Inner", "org.team.System"), rejected);
            assertTrue(result.notes().stream().allMatch(note -> note.code().equals(Finding.Codes.OPMODE_REJECTED)));
            assertTrue(result.notes().stream().anyMatch(note -> note.subject().equals("org.team.Both") && note.detail().contains("both")));
            assertTrue(result.notes().stream().anyMatch(note -> note.subject().equals("org.team.Hidden2") && note.detail().contains("not public")));
        }
    }

    @Test
    void opModesWithOneNameAreToldApartByTheirClasses(@TempDir Path temp) throws IOException {
        Path program = Fixtures.classFolder(temp.resolve("classes"), Map.of(
                PKG + "A", Fixtures.annotated(PKG + "A", OP_MODE, TELEOP, named("Drive", "")),
                PKG + "B", Fixtures.annotated(PKG + "B", OP_MODE, TELEOP, named("Drive", ""))));

        try (ProgramScan scan = ProgramScan.open(List.of(program, library(temp)))) {
            ProgramScan.OpModes result = scan.opModesIn(List.of(program));

            assertEquals(List.of("Drive-A", "Drive-B"), result.found().stream().map(OpModeDescriptor::name).toList());
            assertEquals(2, result.notes().stream().filter(note -> note.code().equals(Finding.Codes.OPMODE_DUPLICATE_NAME)).count());
        }
    }

    @Test
    void aSuperclassThatIsNotOnTheClassPathMeansTheSdkCouldNotLoadTheClass(@TempDir Path temp) throws IOException {
        Path program = Fixtures.classFolder(temp.resolve("classes"), Map.of(
                PKG + "Lost", Fixtures.annotated(PKG + "Lost", "com/missing/Base", TELEOP, named("Lost", ""))));

        try (ProgramScan scan = ProgramScan.open(List.of(program))) {
            ProgramScan.OpModes result = scan.opModesIn(List.of(program));

            assertTrue(result.found().isEmpty());
            assertTrue(result.notes().get(0).detail().contains("leaves the class path"));
        }
    }

    private static byte[] registrarClass(String name, String annotation) {
        return Fixtures.classFile(name, "java/lang/Object", ACC_PUBLIC, writer -> {
            MethodVisitor method = writer.visitMethod(
                    ACC_PUBLIC | ACC_STATIC, "register", "(Lcom/qualcomm/robotcore/eventloop/opmode/AnnotatedOpModeManager;)V", null, null);
            method.visitAnnotation(annotation, true).visitEnd();
            method.visitCode();
            method.visitInsn(RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        });
    }

    @Test
    void methodsThatRegisterOpModesInCodeAreFoundInTheProgramAndItsLibraries(@TempDir Path temp) throws IOException {
        String registrar = "Lcom/qualcomm/robotcore/eventloop/opmode/OpModeRegistrar;";
        Path program = Fixtures.classFolder(temp.resolve("classes"), Map.of(
                PKG + "Plain", Fixtures.plainClass(PKG + "Plain", "java/lang/Object"),
                PKG + "Menu", registrarClass(PKG + "Menu", registrar)));
        Path library = Fixtures.classJar(temp.resolve("panels.jar"), Map.of(
                "com/acme/Handler", registrarClass("com/acme/Handler", registrar),
                "com/acme/Other", registrarClass("com/acme/Other", "Lcom/acme/SomethingElse;")));

        try (ProgramScan scan = ProgramScan.open(List.of(program, library))) {
            List<Finding> found = scan.opModeRegistrarsIn(List.of(program, library));

            assertEquals(List.of("org.team.Menu", "com.acme.Handler"), found.stream().map(Finding::subject).toList());
            assertTrue(found.stream().allMatch(finding -> finding.code().equals(Finding.Codes.OPMODE_REGISTRAR)));
            assertEquals(
                    "register(Lcom/qualcomm/robotcore/eventloop/opmode/AnnotatedOpModeManager;)V", found.get(0).member());
            assertTrue(scan.opModesIn(List.of(program, library)).found().isEmpty(), "such OpModes are pointed out, not listed");
        }
    }

    /** The simulator's Android layer, reduced to what these tests need: a Log with one method, and a Context. */
    private static Path shim(Path folder) throws IOException {
        return Fixtures.classFolder(folder.resolve("shim"), Map.of(
                "android/util/Log", Fixtures.classFile("android/util/Log", "java/lang/Object", ACC_PUBLIC, writer -> {
                    MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "d", "(Ljava/lang/String;Ljava/lang/String;)I", null, null);
                    method.visitCode();
                    method.visitInsn(ICONST_0);
                    method.visitInsn(IRETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                    writer.visitField(ACC_PUBLIC | ACC_STATIC, "LEVEL", "I", null, null).visitEnd();
                })));
    }

    private static List<Finding> compatibility(Path temp, Map<String, byte[]> team, boolean withShim) throws IOException {
        Path program = Fixtures.classFolder(temp.resolve("team"), team);
        try (ProgramScan scan = ProgramScan.open(List.of(program, library(temp)))) {
            return scan.compatibilityOf(List.of(program), withShim ? AndroidApi.of(List.of(shim(temp))) : null);
        }
    }

    private static Finding only(List<Finding> findings, String code) {
        List<Finding> matching = findings.stream().filter(finding -> finding.code().equals(code)).toList();
        assertEquals(1, matching.size(), code + " in " + findings);
        return matching.get(0);
    }

    @Test
    void androidClassesAndMembersTheSimulatorLacksAreNamedWithClassAndMethod(@TempDir Path temp) throws IOException {
        List<Finding> findings = compatibility(temp, Map.of(
                "org/team/Robot", Fixtures.withMethod("org/team/Robot", method -> {
                    method.visitLdcInsn("tag");
                    method.visitLdcInsn("text");
                    method.visitMethodInsn(INVOKESTATIC, "android/util/Log", "d", "(Ljava/lang/String;Ljava/lang/String;)I", false);
                    method.visitInsn(POP);
                    method.visitMethodInsn(INVOKESTATIC, "android/util/Log", "wtf", "()V", false);
                    method.visitFieldInsn(GETSTATIC, "android/util/Log", "LEVEL", "I");
                    method.visitFieldInsn(GETSTATIC, "android/util/Log", "NOPE", "I");
                    method.visitInsn(POP);
                    method.visitMethodInsn(INVOKESTATIC, "android/graphics/Bitmap", "createBitmap", "()V", false);
                })), true);

        Finding member = findings.stream().filter(f -> f.code().equals(Finding.Codes.ANDROID_MEMBER_MISSING) && f.detail().contains("wtf")).findFirst().orElseThrow();
        assertEquals("org.team.Robot", member.subject());
        assertEquals("run()V", member.member());
        assertTrue(findings.stream().anyMatch(f -> f.code().equals(Finding.Codes.ANDROID_MEMBER_MISSING) && f.detail().contains("NOPE")));
        assertFalse(findings.stream().anyMatch(f -> f.detail().contains("Log.d(") || f.detail().contains("LEVEL")), "what the simulator has is not reported");
        assertEquals("org.team.Robot", only(findings, Finding.Codes.ANDROID_CLASS_MISSING).subject());
        assertTrue(only(findings, Finding.Codes.ANDROID_CLASS_MISSING).detail().contains("android.graphics.Bitmap"));
    }

    @Test
    void withoutTheSimulatorsClassesAndroidIsNotJudgedAndTheSkipIsSaid(@TempDir Path temp) throws IOException {
        List<Finding> findings = compatibility(temp, Map.of(
                "org/team/Robot", Fixtures.withMethod("org/team/Robot", method ->
                        method.visitMethodInsn(INVOKESTATIC, "android/graphics/Bitmap", "createBitmap", "()V", false))), false);

        assertEquals(List.of(Finding.Codes.ANDROID_CHECK_SKIPPED), findings.stream().map(Finding::code).toList());
    }

    @Test
    void classesOnNoClassPathAreReportedButTheJdkAndTheLibrariesAreNot(@TempDir Path temp) throws IOException {
        List<Finding> findings = compatibility(temp, Map.of(
                "org/team/Robot", Fixtures.withMethod("org/team/Robot", method -> {
                    method.visitTypeInsn(NEW, "java/util/ArrayList");
                    method.visitInsn(POP);
                    method.visitTypeInsn(NEW, OP_MODE);
                    method.visitInsn(POP);
                    method.visitTypeInsn(NEW, "org/json/JSONObject");
                    method.visitInsn(POP);
                })), true);

        Finding missing = only(findings, Finding.Codes.CLASS_NOT_PROVIDED);
        assertTrue(missing.detail().contains("org.json.JSONObject"));
        assertEquals("run()V", missing.member());
    }

    @Test
    void nativeLibraryLoadsNameTheLibrary(@TempDir Path temp) throws IOException {
        List<Finding> findings = compatibility(temp, Map.of(
                "org/team/Native", Fixtures.withMethod("org/team/Native", method -> {
                    method.visitLdcInsn("vision");
                    method.visitMethodInsn(INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", false);
                    method.visitInsn(ACONST_NULL);
                    method.visitMethodInsn(INVOKESTATIC, "java/lang/System", "load", "(Ljava/lang/String;)V", false);
                })), true);

        List<Finding> loads = findings.stream().filter(f -> f.code().equals(Finding.Codes.NATIVE_LIBRARY_LOAD)).toList();
        assertEquals(2, loads.size());
        assertTrue(loads.get(0).detail().contains("loadLibrary(\"vision\")"), loads.get(0).detail());
        assertFalse(loads.get(1).detail().contains("\""), loads.get(1).detail());
    }

    @Test
    void threadsExecutorsAndNetworkConnectionsAreRecognised(@TempDir Path temp) throws IOException {
        List<Finding> findings = compatibility(temp, Map.of(
                "org/team/Busy", Fixtures.withMethod("org/team/Busy", method -> {
                    method.visitTypeInsn(NEW, "java/lang/Thread");
                    method.visitInsn(DUP);
                    method.visitMethodInsn(INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false);
                    method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
                    method.visitMethodInsn(INVOKESTATIC, "java/util/concurrent/Executors", "newFixedThreadPool", "(I)Ljava/util/concurrent/ExecutorService;", false);
                    method.visitInsn(POP);
                    method.visitTypeInsn(NEW, "java/net/Socket");
                    method.visitInsn(POP);
                    method.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false);
                }),
                "org/team/Worker", Fixtures.plainClass("org/team/Worker", "java/lang/Thread")), true);

        assertEquals(
                List.of("org.team.Busy", "org.team.Busy", "org.team.Busy", "org.team.Worker"),
                findings.stream().filter(f -> f.code().equals(Finding.Codes.THREAD_USE)).map(Finding::subject).toList());
        assertEquals(2, findings.stream().filter(f -> f.code().equals(Finding.Codes.NETWORK_USE)).count());
        assertTrue(findings.stream().filter(f -> f.code().equals(Finding.Codes.NETWORK_USE)).allMatch(f -> f.member().equals("run()V")));
    }

    @Test
    void theSameThingSeenTwiceInOneMethodIsReportedOnce(@TempDir Path temp) throws IOException {
        List<Finding> findings = compatibility(temp, Map.of(
                "org/team/Twice", Fixtures.withMethod("org/team/Twice", method -> {
                    for (int i = 0; i < 3; i++) {
                        method.visitMethodInsn(INVOKESTATIC, "android/util/Log", "wtf", "()V", false);
                    }
                })), true);

        assertEquals(1, findings.stream().filter(f -> f.code().equals(Finding.Codes.ANDROID_MEMBER_MISSING)).count());
    }

    @Test
    void nativeLibrariesInAnAarAreOneFindingNamingThem(@TempDir Path temp) throws IOException {
        Path aar = Fixtures.jar(temp.resolve("lib.aar"), Map.of(
                "jni/arm64-v8a/libfoo.so", new byte[1], "jni/armeabi-v7a/libfoo.so", new byte[1], "classes.jar", new byte[1]));
        Path plain = Fixtures.jar(temp.resolve("plain.aar"), Map.of("classes.jar", new byte[1]));

        Finding finding = NativeLibraries.inAar("org.acme:lib:1.0", aar).orElseThrow();

        assertEquals(Finding.Codes.NATIVE_LIBRARY_PACKAGED, finding.code());
        assertEquals("org.acme:lib:1.0", finding.subject());
        assertTrue(finding.detail().contains("arm64-v8a/libfoo.so") && finding.detail().contains("armeabi-v7a/libfoo.so"));
        assertTrue(NativeLibraries.inAar("org.acme:plain:1.0", plain).isEmpty());
    }

    /** Guards the helper above: the scan reads a method's class through the same code path as a jar entry. */
    @Test
    void aJarOnTheClassPathAndAFolderAreScannedAlike(@TempDir Path temp) throws IOException {
        byte[] drive = Fixtures.annotated(PKG + "Drive", OP_MODE, TELEOP, named("Drive", ""));
        Path folder = Fixtures.classFolder(temp.resolve("folder"), Map.of(PKG + "Drive", drive));
        Path jar = Fixtures.classJar(temp.resolve("drive.jar"), Map.of(PKG + "Drive", drive));
        List<List<OpModeDescriptor>> results = new ArrayList<>();
        for (Path root : List.of(folder, jar)) {
            try (ProgramScan scan = ProgramScan.open(List.of(root, library(temp)))) {
                results.add(scan.opModesIn(List.of(root)).found());
            }
        }

        assertEquals(results.get(0), results.get(1));
        assertEquals(1, results.get(0).size());
    }
}
