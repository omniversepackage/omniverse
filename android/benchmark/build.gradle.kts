plugins {
    alias(libs.plugins.android.test)
}

// Macrobenchmark + Baseline Profile generation (PLAN.md §9). Runs against the app's `benchmark`
// build type (R8, not debuggable, profileable) on a connected device:
//   .\gradlew.bat :android:benchmark:connectedBenchmarkAndroidTest
android {
    namespace = "com.yodesla.omniverse.benchmark"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Target package of the benchmark build type.
        testInstrumentationRunnerArguments["targetPackage"] = "com.yodesla.omniverse.bench"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    targetProjectPath = ":android:app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.uiautomator)
    implementation(libs.test.ext.junit)
    implementation(libs.test.runner)
}
