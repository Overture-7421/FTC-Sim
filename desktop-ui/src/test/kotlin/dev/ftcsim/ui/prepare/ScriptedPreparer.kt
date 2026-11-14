package dev.ftcsim.ui.prepare

import dev.ftcsim.ui.host.ProgramPreparer
import java.nio.file.Path

/**
 * A preparer that does nothing by itself: the test calls its listener, from the test thread, exactly as a real
 * preparer would from its own, and sees every request and cancellation.
 */
class ScriptedPreparer : ProgramPreparer {
    val requests = ArrayList<ProgramPreparer.Request>()
    var cancels = 0
    var failToStart: RuntimeException? = null
    var listener: ProgramPreparer.Listener? = null

    override fun inspect(folder: Path): ProgramPreparer.Inspection =
        ProgramPreparer.Inspection(ProgramPreparer.Kind.PROJECT, folder.fileName.toString(), "A project.")

    override fun prepare(request: ProgramPreparer.Request, listener: ProgramPreparer.Listener): ProgramPreparer.Job {
        failToStart?.let { throw it }
        requests.add(request)
        this.listener = listener
        return ProgramPreparer.Job { cancels++ }
    }

    fun step(number: Int, of: Int = 3, label: String = "Step $number") = listener!!.step(ProgramPreparer.Step(number, of, label))

    fun finish(folder: Path = Path.of("C:\\prepared"), warnings: List<String> = emptyList(), log: Path? = null) =
        listener!!.finished(ProgramPreparer.Prepared(folder, "Prepared", "11.1.0", listOf("A", "B"), warnings, log))

    fun fail(code: String = "COMPILATION_FAILED", message: String = "It did not compile.", details: List<String> = emptyList(), log: Path? = null) =
        listener!!.failed(ProgramPreparer.Failure(code, message, details, log))
}
