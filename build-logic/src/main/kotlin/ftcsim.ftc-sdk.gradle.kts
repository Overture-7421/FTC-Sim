import dev.ftcsim.build.AarAcceptsJar
import dev.ftcsim.build.AarToJar
import dev.ftcsim.build.FtcSdkExtension
import dev.ftcsim.build.PreferAar

plugins {
    `java-library`
}

val artifactType = Attribute.of("artifactType", String::class.java)

val ftcSdk = extensions.create<FtcSdkExtension>("ftcSdk")

dependencies {
    registerTransform(AarToJar::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }
    attributesSchema.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE) {
        compatibilityRules.add(AarAcceptsJar::class)
        disambiguationRules.add(PreferAar::class)
    }
}

// The SDK is published only as Android AARs; ask for exactly that variant. Its third-party libraries come
// along, but the Android UI stack does not: the android-shim module stands in for the framework.
val ftcSdkAars by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "androidx.appcompat")
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("aar"))
    }
    dependencies.addAllLater(
        ftcSdk.version.zip(ftcSdk.modules) { version, modules ->
            modules.map { project.dependencies.create("org.firstinspires.ftc:$it:$version") }
        },
    )
}

val ftcSdkJars: FileCollection = ftcSdkAars.incoming.artifactView {
    attributes { attribute(artifactType, "jar") }
}.files

// Consumers decide where the SDK goes: sdk-common compiles against it, while the acceptance tests keep it out
// of the host class loader and hand it to the robot class loader instead.
ftcSdk.jars.from(ftcSdkJars)
