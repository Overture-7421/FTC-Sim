package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.link.WorkerEnvironment;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppFoldersTest {

    @TempDir
    Path root;

    @Test
    void theFoldersAreThoseOfTheUserAndTheWorkersOwnDefaultForNativesIsTheSameOne() {
        AppFolders folders = AppFolders.forThisUser(Machine.real());

        assertEquals(WorkerEnvironment.defaultNativeDirectory(), folders.natives(), "two natives caches would unpack the engine twice");
        assertEquals(folders.base().resolve("logs"), folders.logs());
        assertEquals("FTC-Sim", folders.base().getFileName().toString());
    }

    @Test
    void aWorkspaceIsShortStableAndNeverSharedByTwoProjectFolders() {
        AppFolders folders = new AppFolders(root.resolve("FTC-Sim"));
        Path project = root.resolve("My Robot Code (v2)").resolve("FtcRobotController");

        Path workspace = folders.workspace(project);

        assertEquals(workspace, folders.workspace(project), "the same project gets the same workspace, so its build resumes");
        assertEquals(workspace, folders.workspace(Path.of(project.toString().toUpperCase())), "Windows paths do not differ by case");
        assertNotEquals(workspace, folders.workspace(root.resolve("Elsewhere").resolve("FtcRobotController")));
        assertEquals(folders.base().resolve("import").resolve("work"), workspace.getParent());
        assertTrue(workspace.getFileName().toString().length() <= 21, workspace.getFileName().toString());
        assertTrue(workspace.getFileName().toString().matches("ftcrobotcont-[0-9a-f]{8}"), workspace.getFileName().toString());
    }

    @Test
    void aProgramIsNamedByItsProjectAndTheStartOfTheHashOfItsSource() {
        AppFolders folders = new AppFolders(root.resolve("FTC-Sim"));
        String fingerprint = "0123456789abcdef".repeat(4);

        Path program = folders.program("Team 7421: Into The Deep?", fingerprint);

        assertEquals(folders.programs().resolve("Team_7421__Into_The_Deep_-0123456789ab"), program);
        assertEquals(folders.programs().resolve("project-0123456789ab"), folders.program("...", fingerprint), "a name that is nothing is replaced");
        assertEquals(40 + 1 + 12, folders.program("x".repeat(100), fingerprint).getFileName().toString().length());
        assertEquals(folders.programs().resolve("Team_7421__Into_The_Deep_-0123456789ab.build.log"), folders.buildLog(program));
    }
}
