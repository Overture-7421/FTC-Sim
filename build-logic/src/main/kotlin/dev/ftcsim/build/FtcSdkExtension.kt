package dev.ftcsim.build

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Which FTC SDK release a module works with, which of its modules it needs, and the jars that resolves to. */
abstract class FtcSdkExtension {
    abstract val version: Property<String>

    abstract val modules: ListProperty<String>

    /** The SDK's classes and its third-party libraries, extracted from the published AARs. */
    abstract val jars: ConfigurableFileCollection

    init {
        modules.convention(listOf("RobotCore", "Hardware", "FtcCommon", "Vision"))
    }
}
