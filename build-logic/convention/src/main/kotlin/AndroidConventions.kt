import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

internal object Sdk {
    const val COMPILE = 37
    const val TARGET = 36
    // Older Fire OS / Android TV boxes are still out there; Compose's floor is 23.
    const val MIN = 23
}

internal fun configureAndroidCommon(ext: CommonExtension) {
    ext.compileSdk = Sdk.COMPILE
    ext.defaultConfig.minSdk = Sdk.MIN
    ext.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    ext.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    ext.buildFeatures.compose = true
}
