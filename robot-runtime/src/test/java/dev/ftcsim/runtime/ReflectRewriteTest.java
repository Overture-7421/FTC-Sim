package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.runtime.fixture.ReflectProbe;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Robot-world reflection matches Android ART for static-final writes and public methods on non-public classes. */
class ReflectRewriteTest {

    @Test
    void fieldSettersAndMethodInvokeAreRedirectedToReflectHooks() throws IOException {
        List<String> calls = FixtureWorld.calls(FixtureWorld.rewritten(ReflectProbe.class));

        for (RewriteRule rule : ReflectRules.all()) {
            String jdkMember = rule.owner() + '.' + rule.name() + rule.descriptor();
            String hookMember = rule.hookOwner() + '.' + rule.hookName() + rule.hookDescriptor();
            assertFalse(calls.contains(jdkMember), jdkMember + " is still called directly");
            assertTrue(calls.contains(hookMember), hookMember + " should replace " + jdkMember);
        }
    }

    @Test
    void androidReflectionSemanticsHoldInsideTheRobotWorldWithoutWeakeningJdkOrCrossPackageRules() throws Exception {
        Object seen;
        try (RobotClassLoader loader = FixtureWorld.loader()) {
            seen = FixtureWorld.run(loader, "ReflectProbe");
        }

        assertEquals(
                List.of(
                        "tag-unaccessible:denied",
                        "tag:ReplacedTag",
                        "holder:NewHolder",
                        "flag:true",
                        "byte:7",
                        "char:z",
                        "short:12",
                        "int:42",
                        "long:99",
                        "float:1.25",
                        "double:2.5",
                        "widened:11.0",
                        "null-primitive:rejected",
                        "bad-type:rejected",
                        "jdk-static-final:denied",
                        "same-pkg-field:55",
                        "same-pkg-method:hi:ok",
                        "cross-pkg-field:denied",
                        "cross-pkg-nonpublic-method:denied",
                        "hidden-public-static:pong:a",
                        "hidden-public-instance:instance:b"),
                seen);
    }
}
