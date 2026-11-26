package dalvik.system;

/**
 * Android's class loader for installed APKs and dex archives on the local filesystem. Used by the FTC SDK's OnBotJava
 * class loader and by libraries such as Sloth that layer a reloading class loader over the application package.
 */
public class PathClassLoader extends BaseDexClassLoader {
    public PathClassLoader(String dexPath, ClassLoader parent) {
        super(dexPath, null, null, parent);
    }

    public PathClassLoader(String dexPath, String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
    }
}
