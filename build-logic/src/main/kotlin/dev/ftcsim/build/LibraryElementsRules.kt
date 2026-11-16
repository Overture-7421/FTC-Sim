package dev.ftcsim.build

import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.MultipleCandidatesDetails

/** A consumer asking for an Android library may also take a plain jar, as Android's own tooling allows. */
abstract class AarAcceptsJar : AttributeCompatibilityRule<LibraryElements> {
    override fun execute(details: CompatibilityCheckDetails<LibraryElements>) {
        val wanted = details.consumerValue?.name
        val offered = details.producerValue?.name
        if (wanted == "aar" && (offered == "jar" || offered == "classes")) {
            details.compatible()
        }
    }
}

/** When a module offers both, the Android library variant wins because it is what the SDK ships. */
abstract class PreferAar : AttributeDisambiguationRule<LibraryElements> {
    override fun execute(details: MultipleCandidatesDetails<LibraryElements>) {
        if (details.consumerValue?.name == "aar") {
            details.candidateValues.firstOrNull { it.name == "aar" }?.let { details.closestMatch(it) }
        }
    }
}
