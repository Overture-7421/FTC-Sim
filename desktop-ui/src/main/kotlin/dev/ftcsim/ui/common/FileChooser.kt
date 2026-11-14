package dev.ftcsim.ui.common

import java.nio.file.Path

/**
 * Asks the person for a file or a folder. Behind an interface so that the screens can be tested without a dialog ever
 * opening; the real one is [AwtFileChooser]. Both calls suspend until the person has chosen or cancelled, without
 * holding up the UI thread.
 */
interface FileChooser {
    /** A file of one of the [extensions] (without the dot; empty for any), or null when the person cancelled. */
    suspend fun chooseFile(title: String, extensions: List<String>, startIn: Path?): Path?

    /** A folder, or null when the person cancelled. */
    suspend fun chooseFolder(title: String, startIn: Path?): Path?
}
