package dev.ftcsim.ui.common

import java.nio.file.Path

/** Answers with what a test put in it, never opens a dialog and writes down what it was asked. */
class FakeFileChooser : FileChooser {
    val asked = ArrayList<String>()
    var file: Path? = null
    var folder: Path? = null

    override suspend fun chooseFile(title: String, extensions: List<String>, startIn: Path?): Path? {
        asked += title
        return file
    }

    override suspend fun chooseFolder(title: String, startIn: Path?): Path? {
        asked += title
        return folder
    }
}
