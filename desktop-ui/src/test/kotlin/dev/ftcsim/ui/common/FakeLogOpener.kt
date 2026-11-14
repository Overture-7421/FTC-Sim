package dev.ftcsim.ui.common

import java.nio.file.Path

/** Opens nothing, writes down what it was asked to open, and answers with the problem a test put in it. */
class FakeLogOpener : LogOpener {
    val opened = ArrayList<Path>()
    var problem: String? = null

    override suspend fun open(file: Path): String? {
        opened.add(file)
        return problem
    }
}
