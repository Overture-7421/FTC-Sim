package dev.ftcsim.ui.common

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The operating system's dialogs: the native file dialog for files and Swing's folder chooser in the Windows look for
 * folders, because the native dialog cannot choose a folder. Both are modal to [owner]. Not covered by tests, which
 * would have to open a real dialog.
 */
class AwtFileChooser(private val owner: () -> Frame?) : FileChooser {
    override suspend fun chooseFile(title: String, extensions: List<String>, startIn: Path?): Path? =
        withContext(Dispatchers.IO) {
            val dialog = FileDialog(owner(), title, FileDialog.LOAD)
            startIn?.let { dialog.directory = it.toString() }
            if (extensions.isNotEmpty()) {
                dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
            }
            dialog.isVisible = true
            val file = dialog.file
            val directory = dialog.directory
            dialog.dispose()
            file?.let { Paths.get(directory ?: "", it) }
        }

    override suspend fun chooseFolder(title: String, startIn: Path?): Path? =
        withContext(Dispatchers.Main) {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser(startIn?.toFile())
            chooser.dialogTitle = title
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            if (chooser.showOpenDialog(owner()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
        }
}
