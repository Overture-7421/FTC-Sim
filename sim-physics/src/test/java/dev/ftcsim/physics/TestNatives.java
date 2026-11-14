package dev.ftcsim.physics;

import java.nio.file.Path;

/** Where the tests find the native library and what it must hash to. */
final class TestNatives {

    static final String RELEASE_SHA256 = "f74b89d91fb305876df3a3c577121d4b2742cb3b0ed642e6828c419755b45b75";
    static final String DEBUG_SHA256 = "e31415d84682ab60ee76c5b80bea24350538faaa7e6f6c020e2d9f362aedfa3b";

    private TestNatives() {}

    static Path directory() {
        return Path.of(System.getProperty("ftcsim.nativeDirectory"));
    }

    static PhysicsWorld newWorld() {
        return PhysicsWorld.create(directory(), RELEASE_SHA256, 0);
    }
}
