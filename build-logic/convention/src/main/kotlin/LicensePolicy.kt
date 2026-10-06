import app.cash.licensee.LicenseeExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * PLAN.md §4.4: only permissive licenses may be linked into the app (it may be sold closed-source).
 * LGPL native libs (FFmpeg audio, libmpv) come later as separately shipped .so files and are
 * allowed explicitly by coordinate, never by a blanket rule. GPL/AGPL: never.
 */
internal fun Project.applyLicensePolicy() {
    pluginManager.apply("app.cash.licensee")
    extensions.configure<LicenseeExtension> {
        allow("Apache-2.0")
        allow("MIT")
        allow("BSD-2-Clause")
        allow("BSD-3-Clause")
        allow("ISC")
        allow("OFL-1.1")
        allowUrl("https://developer.android.com/studio/terms.html")
    }
}
