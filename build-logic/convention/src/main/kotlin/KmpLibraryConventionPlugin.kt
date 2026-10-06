import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Kotlin Multiplatform library for `core:*`.
 * Target: jvm — consumed by the Android app (and later desktop) and runs the fast unit tests.
 * iOS/tvOS targets are added in Phase 6, so commonMain must stay free of java.* / android.*.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
            jvm {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
        applyLicensePolicy()
    }
}
