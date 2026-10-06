plugins {
    `kotlin-dsl`
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    compileOnly(libs.licensee.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "omniverse.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "omniverse.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "omniverse.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
