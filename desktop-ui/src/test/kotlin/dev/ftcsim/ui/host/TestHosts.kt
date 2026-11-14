package dev.ftcsim.ui.host

import dev.ftcsim.ui.link.FakeSimulatorLink
import java.nio.file.Files
import java.nio.file.Path

/** Where the tests of one run keep what a fake preparer writes: under the build directory, emptied when the first test asks. */
private val workRoot: Path by lazy {
    val root = Path.of("build", "test-work")
    root.toFile().deleteRecursively()
    Files.createDirectories(root)
}

/** A new empty folder under the module's build directory, where a fake preparer may write its "prepared programs". */
fun newWorkFolder(): Path = Files.createTempDirectory(workRoot, "run")

/** A fake host for tests: a link that opens at once, unless told otherwise, and a preparer that plays on [scheduler]. */
fun testHost(
    scheduler: ManualScheduler = ManualScheduler(),
    work: Path = newWorkFolder(),
    links: FakeAppHost.LinkOpener = FakeAppHost.LinkOpener { FakeSimulatorLink() },
): FakeAppHost = FakeAppHost(FakeProgramPreparer(scheduler, work), links)
