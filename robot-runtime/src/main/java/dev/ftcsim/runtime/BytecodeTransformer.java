package dev.ftcsim.runtime;

/** Changes the bytes of a class as the robot class loader defines it. */
@FunctionalInterface
public interface BytecodeTransformer {

    /** Returns the bytes to define; implementations must not load classes while transforming. */
    byte[] transform(String internalName, byte[] classBytes);

    BytecodeTransformer IDENTITY = (internalName, classBytes) -> classBytes;
}
