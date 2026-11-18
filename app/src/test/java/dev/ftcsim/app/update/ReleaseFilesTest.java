package dev.ftcsim.app.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The installer script and the release workflow are text that nothing compiles until a release is made, and the application
 * finds its update by the names they give: these keep them in step with {@link GitHubUpdater}, and with each other, without
 * running Inno Setup or GitHub.
 */
class ReleaseFilesTest {
    private static String read(String property) throws IOException {
        return Files.readString(Path.of(System.getProperty(property)));
    }

    @Test
    void theInstallerIsNamedAsTheApplicationLooksForItAndInstallsForTheUserAlone() throws IOException {
        String script = read("ftcsim.installerScript");

        assertTrue(script.contains("OutputBaseFilename=FTC-Sim-{#AppVersion}-Setup"), "FTC-Sim-<version>-Setup.exe, GitHubUpdater.INSTALLER_NAME");
        assertTrue(GitHubUpdater.INSTALLER_NAME.matcher("FTC-Sim-1.2.3-Setup.exe").matches());
        assertTrue(GitHubUpdater.INSTALLER_NAME.matcher("FTC-Sim-1.2.3-rc.1-Setup.exe").matches());
        assertTrue(script.contains("PrivilegesRequired=lowest"), "no administrator rights");
        assertTrue(script.contains("DefaultDirName={localappdata}\\Programs\\{#AppName}"), "nothing outside the user's profile");
        assertTrue(script.contains("AppId={{"), "one identity, so that a new installer upgrades the old installation");
        assertTrue(script.contains("CloseApplications=yes"), "a running FTC-Sim is asked to close, not an error");
    }

    @Test
    void theShortcutsStartTheBundledJavaWithTheLaunchersOptionsAndWithoutAConsoleWindow() throws IOException {
        String script = read("ftcsim.installerScript");

        assertTrue(script.contains("runtime\\bin\\javaw.exe"), "javaw, not java: no console window");
        assertTrue(script.contains("runtime-options"), "the options FTC-Sim.cmd starts the Java with, which the self test checks for");
        assertTrue(script.contains("-jar \"\"\"\"{app}\\lib\\app.jar"), "the application's jar");
        assertTrue(script.contains("#define JavaArguments \"-XX"), "the argument file must not come first: a Windows shortcut drops parameters that start with @");
        assertTrue(script.contains("Check: StartsAfterInstall") && script.contains("{param:NORUN|0}"), "a test can install without a window");
        long runLines = script.lines().filter(line -> line.startsWith("Filename:")).count();
        assertEquals(1, runLines, "one [Run] entry: the application starts again after a silent update");
        assertTrue(!script.lines().filter(line -> line.startsWith("Filename:")).anyMatch(line -> line.contains("skipifsilent")),
                "a silent install, which is how an update goes, starts the application");
    }

    @Test
    void theWorkflowBuildsTheFilesTheApplicationFetchesAndChecksTheInstallerBeforePublishing() throws IOException {
        String workflow = read("ftcsim.releaseWorkflow");

        assertTrue(workflow.contains("FTC-Sim-$env:VERSION-Setup.exe"), "the installer's name");
        assertTrue(workflow.contains(GitHubUpdater.CHECKSUMS_NAME), "the checksums file the application checks against");
        assertTrue(workflow.contains("-PappVersion=$env:VERSION"), "the version goes into the jar, or the application never looks");
        assertTrue(workflow.contains("--self-test"), "the distribution and the installed program are tried before a release");
        assertTrue(workflow.contains("/NORUN=1") && workflow.contains("unins000.exe"), "the installer is installed and uninstalled once");
        assertTrue(workflow.contains("gh @arguments") && workflow.contains("'release', 'create'"), "published with the GitHub CLI");
        assertTrue(workflow.indexOf("Try the installer") < workflow.indexOf("Publish the release"), "tried before it is published");
        assertTrue(workflow.contains("on:\n  workflow_dispatch:") || workflow.contains("on:\r\n  workflow_dispatch:"), "run by hand with a version");
        assertTrue(!workflow.contains("push:") && !workflow.contains("tags:"), "no trigger but the run by hand: a tag pushed by hand makes no release");
        assertTrue(workflow.contains("git ls-remote --tags origin"), "a version that already has a tag is refused before anything is built");
        assertTrue(workflow.contains("'--target', $env:GITHUB_SHA"), "the release creates its tag on the commit it was built from");
        assertTrue(workflow.contains("contents: write"), "to create the release");
    }
}
