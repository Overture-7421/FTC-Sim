package dev.ftcsim.runtime;

import dev.ftcsim.contracts.hooks.Hooks;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Loads a robot program, the FTC SDK and every library the program uses into a world of their own, so the
 * simulator's classes and the robot's classes can never collide.
 *
 * <p>Classes are defined child-first from the robot classpath after passing through a
 * {@link BytecodeTransformer}; only the JDK and the packages the host explicitly shares are delegated. A
 * class that cannot be transformed is a hard error rather than being loaded unmodified, because an
 * uninstrumented class would silently escape the virtual clock.
 */
public final class RobotClassLoader extends ClassLoader implements Closeable {
    static {
        registerAsParallelCapable();
    }

    private final List<ClassSource> sources = new ArrayList<>();
    private final ClassLoader host;
    private final Predicate<String> sharedWithHost;
    private final BytecodeTransformer transformer;
    private final Map<ClassSource, ProtectionDomain> domains = new ConcurrentHashMap<>();

    /**
     * @param classpath      directories and jars holding the robot program and its dependencies, in search order
     * @param host           loader that owns the classes the robot world shares with the simulator
     * @param sharedWithHost decides, by binary class name, which classes come from the host instead
     */
    public RobotClassLoader(
            List<Path> classpath, ClassLoader host, Predicate<String> sharedWithHost, BytecodeTransformer transformer)
            throws IOException {
        super(Hooks.WORLD_LOADER_NAME, ClassLoader.getPlatformClassLoader());
        this.host = host;
        this.sharedWithHost = sharedWithHost;
        this.transformer = transformer;
        try {
            for (Path entry : classpath) {
                sources.add(ClassSource.of(entry));
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (sharedWithHost.test(name)) {
                    loaded = host.loadClass(name);
                } else if (name.startsWith("java.")) {
                    loaded = getParent().loadClass(name);
                } else {
                    Optional<Class<?>> own = defineFromSources(name);
                    loaded = own.isPresent() ? own.get() : getParent().loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return defineFromSources(name).orElseThrow(() -> new ClassNotFoundException(name));
    }

    /**
     * Empty when no source has the class; a {@link ClassNotFoundException} when a source has it but it cannot
     * be read or instrumented, so that the reason is never mistaken for plain absence.
     */
    private Optional<Class<?>> defineFromSources(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        for (ClassSource source : sources) {
            Optional<byte[]> original;
            try {
                original = source.read(resource);
            } catch (IOException e) {
                throw new ClassNotFoundException(name + " could not be read from " + source.location(), e);
            }
            if (original.isPresent()) {
                return Optional.of(define(name, source, original.get()));
            }
        }
        return Optional.empty();
    }

    private Class<?> define(String name, ClassSource source, byte[] original) throws ClassNotFoundException {
        byte[] bytes;
        try {
            bytes = transformer.transform(name.replace('.', '/'), original);
        } catch (RuntimeException e) {
            throw new ClassNotFoundException(
                    name + " was found in " + source.location() + " but could not be instrumented: " + e.getMessage(), e);
        }
        ensurePackage(name);
        return defineClass(name, bytes, 0, bytes.length, domainFor(source));
    }

    private void ensurePackage(String className) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String packageName = className.substring(0, dot);
        if (getDefinedPackage(packageName) == null) {
            try {
                definePackage(packageName, null, null, null, null, null, null, null);
            } catch (IllegalArgumentException definedConcurrently) {
                // another thread defined the same package first
            }
        }
    }

    private ProtectionDomain domainFor(ClassSource source) {
        return domains.computeIfAbsent(
                source, s -> new ProtectionDomain(new CodeSource(s.location(), (Certificate[]) null), null, this, null));
    }

    @Override
    protected URL findResource(String name) {
        for (ClassSource source : sources) {
            try {
                Optional<URL> url = source.locate(name);
                if (url.isPresent()) {
                    return url.get();
                }
            } catch (IOException e) {
                // a source that cannot answer simply does not have the resource
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        List<URL> found = new ArrayList<>();
        for (ClassSource source : sources) {
            try {
                source.locate(name).ifPresent(found::add);
            } catch (IOException e) {
                // see findResource
            }
        }
        return Collections.enumeration(found);
    }

    /** Reads straight from the sources so that no URL connection keeps a jar open. */
    @Override
    public InputStream getResourceAsStream(String name) {
        for (ClassSource source : sources) {
            try {
                Optional<byte[]> bytes = source.read(name);
                if (bytes.isPresent()) {
                    return new java.io.ByteArrayInputStream(bytes.get());
                }
            } catch (IOException e) {
                // fall through to the next source
            }
        }
        return super.getResourceAsStream(name);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (ClassSource source : sources) {
            try {
                source.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        sources.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
