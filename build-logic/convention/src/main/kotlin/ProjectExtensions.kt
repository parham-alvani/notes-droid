import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The single place the SDK levels are declared. Every module inherits these. */
internal object AndroidSdk {
    const val COMPILE = 37
    const val TARGET = 37

    /**
     * API 29 is the floor. The AndroidKeyStore GCM usage and the `java.nio.file`
     * surface both want a modern platform, and the one target device is far
     * newer than this.
     */
    const val MIN = 29
}

/** AGP 9.4.0 requires JDK 17 and rejects anything newer. */
internal const val JVM_TOOLCHAIN = 17

/**
 * Must stay `internal`. These files are compiled into the default package, so a
 * public `Project.libs` would land on every build script's classpath and shadow
 * Gradle's own generated version-catalog accessor -- which fails as
 * "Unresolved reference 'androidx'" on the first `libs.androidx.*` line.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
