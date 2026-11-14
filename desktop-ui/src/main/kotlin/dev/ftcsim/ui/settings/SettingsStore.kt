package dev.ftcsim.ui.settings

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The user's settings: read once from [file] when the store is made, changed through [update] and written back after
 * every change on [writer], so that neither reading nor writing a file stalls the UI thread more than the one
 * small read at the start. A missing file gives the defaults. A file that cannot be understood also gives the
 * defaults, is moved aside to `<name>.corrupt` so that nothing is lost, and is reported through [problem]. With no
 * file, settings live only as long as the process.
 *
 * A write goes to a temporary file that then replaces the real one, so a crash cannot leave half a file.
 */
class SettingsStore(
    private val file: Path?,
    private val writer: Executor,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val mutableState = MutableStateFlow(UserSettings())
    private val mutableProblem = MutableStateFlow<String?>(null)
    private val listeners = CopyOnWriteArrayList<(UserSettings) -> Unit>()
    private val writePending = AtomicBoolean(false)

    val state: StateFlow<UserSettings> = mutableState.asStateFlow()

    /** What went wrong with reading or saving, in words for the person, or null. */
    val problem: StateFlow<String?> = mutableProblem.asStateFlow()

    init {
        mutableState.value = load()
    }

    /** Changes the settings and saves them; [change] must be a pure function of its argument. */
    fun update(change: (UserSettings) -> UserSettings) {
        val before = mutableState.value
        mutableState.update(change)
        val after = mutableState.value
        if (after != before) {
            listeners.forEach { it(after) }
            scheduleWrite()
        }
    }

    /** Calls [listener] with the new settings after every change, on the thread that made it, and once now. */
    fun onChange(listener: (UserSettings) -> Unit) {
        listeners += listener
        listener(mutableState.value)
    }

    fun dismissProblem() {
        mutableProblem.value = null
    }

    /** Finishes the writes that are waiting. */
    override fun close() = onClose()

    private fun load(): UserSettings {
        val path = file ?: return UserSettings()
        if (!Files.exists(path)) return UserSettings()
        return try {
            val properties = Properties()
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { properties.load(it) }
            require(properties.getProperty(SettingsCodec.FORMAT_KEY)?.trim() == SettingsCodec.FORMAT.toString()) {
                "it is not a settings file of this version"
            }
            SettingsCodec.decode(properties)
        } catch (failure: IOException) {
            quarantine(path, failure)
        } catch (failure: IllegalArgumentException) {
            quarantine(path, failure)
        }
    }

    private fun quarantine(path: Path, failure: Exception): UserSettings {
        val aside = path.resolveSibling(path.fileName.toString() + ".corrupt")
        val kept = try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING)
            "The old file was kept as ${aside.fileName}."
        } catch (moveFailure: IOException) {
            "The old file could not be moved aside: ${moveFailure.message}"
        }
        mutableProblem.value = "The saved settings could not be read (${failure.message}), so the defaults are used. $kept"
        return UserSettings()
    }

    private fun scheduleWrite() {
        if (file != null && writePending.compareAndSet(false, true)) {
            writer.execute(::write)
        }
    }

    private fun write() {
        writePending.set(false)
        val path = file ?: return
        val settings = mutableState.value
        try {
            Files.createDirectories(path.toAbsolutePath().parent)
            val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use {
                SettingsCodec.encode(settings).store(it, "FTC-Sim user interface settings")
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: IOException) {
            mutableProblem.value = "The settings could not be saved: ${failure.message}"
        }
    }

    companion object {
        private const val CLOSE_WAIT_SECONDS = 3L

        /** A store that writes on a thread of its own and, when closed, waits for the last write. */
        fun forFile(file: Path?): SettingsStore {
            val pool: ExecutorService = Executors.newSingleThreadExecutor { task ->
                Thread(task, "settings-writer").also { it.isDaemon = true }
            }
            return SettingsStore(file, pool) {
                pool.shutdown()
                pool.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }
}
