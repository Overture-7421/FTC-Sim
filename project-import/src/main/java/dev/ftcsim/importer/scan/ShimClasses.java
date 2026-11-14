package dev.ftcsim.importer.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** {@link AndroidApi} over the class files of the simulator's Android layer, with the JDK's classes behind them. */
final class ShimClasses implements AndroidApi {

    /** A class's place in the hierarchy and what it declares. */
    private record Declared(String superName, List<String> interfaces, Set<String> methods, Set<String> fields) {}

    private final Map<String, Declared> shim = new HashMap<>();
    private final Map<String, Optional<Declared>> jdk = new HashMap<>();

    private ShimClasses() {}

    static ShimClasses of(List<Path> classPath) throws IOException {
        ShimClasses api = new ShimClasses();
        for (Path root : classPath) {
            ClassFiles.forEach(root, (name, bytes) -> api.shim.putIfAbsent(name, read(bytes)));
        }
        return api;
    }

    @Override
    public boolean hasClass(String internalName) {
        return shim.containsKey(internalName);
    }

    @Override
    public boolean hasMethod(String owner, String name, String descriptor) {
        return owner.startsWith("[") || !Boolean.FALSE.equals(find(owner, name + descriptor, true, new HashSet<>()));
    }

    @Override
    public boolean hasField(String owner, String name) {
        return owner.startsWith("[") || !Boolean.FALSE.equals(find(owner, name, false, new HashSet<>()));
    }

    /** TRUE when found, FALSE when the whole hierarchy is known and lacks it, null when the hierarchy cannot be followed. */
    private Boolean find(String owner, String key, boolean method, Set<String> visited) {
        if (!visited.add(owner)) {
            return Boolean.FALSE;
        }
        Declared declared = declaredBy(owner);
        if (declared == null) {
            return null;
        }
        if ((method ? declared.methods() : declared.fields()).contains(key)) {
            return Boolean.TRUE;
        }
        boolean unknown = false;
        List<String> parents = new ArrayList<>(declared.interfaces());
        if (declared.superName() != null) {
            parents.add(0, declared.superName());
        }
        for (String parent : parents) {
            Boolean found = find(parent, key, method, visited);
            if (Boolean.TRUE.equals(found)) {
                return Boolean.TRUE;
            }
            unknown |= found == null;
        }
        return unknown ? null : Boolean.FALSE;
    }

    private Declared declaredBy(String name) {
        Declared inShim = shim.get(name);
        return inShim != null ? inShim : jdk.computeIfAbsent(name, ShimClasses::readFromJdk).orElse(null);
    }

    private static Optional<Declared> readFromJdk(String name) {
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
            return in == null ? Optional.empty() : Optional.of(read(in.readAllBytes()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Declared read(byte[] bytes) {
        Set<String> methods = new HashSet<>();
        Set<String> fields = new HashSet<>();
        String[] header = new String[1];
        List<String> interfaces = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] implemented) {
                header[0] = superName;
                if (implemented != null) {
                    interfaces.addAll(List.of(implemented));
                }
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.add(name + descriptor);
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                fields.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Declared(header[0], interfaces, methods, fields);
    }
}
